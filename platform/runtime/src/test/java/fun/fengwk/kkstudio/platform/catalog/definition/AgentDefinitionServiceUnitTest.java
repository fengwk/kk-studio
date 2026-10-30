package fun.fengwk.kkstudio.platform.catalog.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** Agent 定义服务的单元契约（仓库与引用解析以 mock 隔离）：字段与配置规范化、内置 Agent 规则、CAS 失败分类、完整性错误映射与删除前置检查。 */
class AgentDefinitionServiceUnitTest {

  private static final String EMPTY_CONFIG = "{\"tools\":[],\"skills\":[],\"subagents\":[]}";

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private AgentDefinitionRepository repository;
  private AgentDefinitionReferenceResolver resolver;
  private AgentModelDefaultVariantResolver variants;
  private PluginAgentDependencies pluginAgentDependencies;
  private AgentDefinitionService service;

  @BeforeEach
  void setUp() {
    repository = mock(AgentDefinitionRepository.class);
    resolver = mock(AgentDefinitionReferenceResolver.class);
    variants = mock(AgentModelDefaultVariantResolver.class);
    pluginAgentDependencies = mock(PluginAgentDependencies.class);
    when(resolver.requireModelForUpdate(any()))
        .thenAnswer(invocation -> model(invocation.getArgument(0), AgentModelKind.MODEL));
    service =
        new AgentDefinitionService(
            repository,
            resolver,
            variants,
            new AgentDefinitionConfigValidator(
                TestRuntimeToolCatalogSource.of(HarnessCatalog.from(List.of()))),
            new AgentDefinitionConfigCodec(objectMapper),
            new AgentEditableSupport(objectMapper),
            pluginAgentDependencies);
  }

  /** 创建持久化规范化的能力列表与全局 model 名；更新保持名称身份并改写模型与 variant。 */
  @Test
  void persistsCanonicalConfigurationAndKeepsIdentity() throws Exception {
    AgentDefinition created =
        createAndCapture(
            create(
                "agent",
                "model",
                config(List.of(), List.of(skillRef("tools", "java"), skillRef("tools", "dev"))),
                null));
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(created.configJson(), AgentDefinitionConfigDTO.class);
    assertEquals(List.of(skillRef("tools", "java"), skillRef("tools", "dev")), stored.getSkills());
    assertEquals(AgentDefinitionType.USER, created.type());
    assertEquals("model", created.modelName());

    AgentDefinition current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    when(repository.getByName("agent")).thenReturn(current);
    AgentDefinitionUpdateDTO update = update("0");
    update.setDescription("updated");
    update.setModel("vendor/other model");
    update.setVariant("quality");
    service.updateAgent("agent", update);

    ArgumentCaptor<AgentDefinition> captor = ArgumentCaptor.forClass(AgentDefinition.class);
    verify(repository).updateByName(captor.capture(), eq(0L));
    AgentDefinition updated = captor.getValue();
    assertEquals("agent", updated.name());
    assertEquals("updated", updated.description());
    assertEquals("vendor/other model", updated.modelName());
    assertEquals("quality", updated.variant());
    verify(resolver).requireModelForUpdate("vendor/other model");
    verify(variants).resolve("vendor/other model", "quality");
  }

  /** 非 canonical 能力名、缺字段与超限输入在写入前确定性拒绝；description 为 text 不截断。 */
  @Test
  void rejectsNonCanonicalOrIncompleteInput() {
    AiValidationException duplicate =
        assertThrows(
            AiValidationException.class,
            () ->
                service.validateImport(
                    "agent",
                    create(
                        "agent",
                        "model",
                        config(
                            List.of(),
                            List.of(skillRef("tools", "java"), skillRef("tools", "java"))),
                        null)));
    assertEquals(
        "agent definition config skills must not contain duplicates: tools/java",
        duplicate.getMessage());
    AiValidationException tool =
        assertThrows(
            AiValidationException.class,
            () ->
                service.validateImport(
                    "agent", create("agent", "model", config(List.of(" read "), List.of()), null)));
    assertEquals(
        "agent definition config tools must contain valid model-visible tool names:  read ",
        tool.getMessage());

    for (String invalidName : List.of(" ", " agent ", "n".repeat(65), "agent/name")) {
      assertThrows(
          AiValidationException.class,
          () ->
              service.validateImport(
                  invalidName, create(invalidName, "model", emptyConfig(), null)));
    }
    assertThrows(AiValidationException.class, () -> service.validateImport("agent", null));
    assertThrows(
        AiValidationException.class,
        () -> service.validateImport(null, create(null, "model", emptyConfig(), null)));
    assertThrows(
        AiValidationException.class,
        () -> service.validateImport("agent", create("agent", null, emptyConfig(), null)));
    for (String invalidModel : List.of(" model ", " model", "model ", "m".repeat(129))) {
      assertThrows(
          AiValidationException.class,
          () ->
              service.validateImport("agent", create("agent", invalidModel, emptyConfig(), null)));
    }
    assertThrows(
        AiValidationException.class,
        () -> service.validateImport("agent", create("agent", "model", null, null)));

    AgentDefinitionCreateDTO longDescription = create("agent", "model", emptyConfig(), null);
    longDescription.setDescription("d".repeat(4096));
    AgentDefinition created = createAndCapture(longDescription);
    assertEquals("d".repeat(4096), created.description());
    assertNull(created.variant());
  }

  /** 非法 model 名、variant、组级 variant 或执行配置在写入前包装为校验错误；null 请求同样确定性拒绝。 */
  @Test
  void wrapsInvalidReferencesAsValidationErrors() {
    AgentDefinitionCreateDTO badRef = create("agent", " model", emptyConfig(), null);
    assertThrows(AiValidationException.class, () -> service.createAgent(badRef));
    assertThrows(AiValidationException.class, () -> service.createAgent(null));

    when(variants.resolve("model", "bad-variant"))
        .thenThrow(new IllegalArgumentException("unknown variant: bad-variant"));
    assertThrows(
        AiValidationException.class,
        () -> service.createAgent(create("agent", "model", emptyConfig(), "bad-variant")));

    when(resolver.requireModelForUpdate("group")).thenReturn(model("group", AgentModelKind.GROUP));
    AiValidationException groupVariant =
        assertThrows(
            AiValidationException.class,
            () -> service.createAgent(create("agent", "group", emptyConfig(), "default")));
    assertEquals(
        "variant must be blank when the selected model is a GROUP", groupVariant.getMessage());
    verify(variants, never()).resolve(eq("group"), any());

    assertThrows(
        AiValidationException.class,
        () ->
            service.createAgent(
                create("agent", "model", config(List.of("no-such-tool"), List.of()), null)));
    verify(repository, never()).create(any());
  }

  /** 创建与更新都按当前 Skill/工具引用做有序加锁校验。 */
  @Test
  void validatesCurrentSkillReferencesOnCreateAndUpdate() {
    SkillRefDTO skillRef = skillRef("tools", "dev");
    createAndCapture(create("agent", "model", config(List.of(), List.of(skillRef)), null));
    verify(resolver).requireReferencedLifecycles(List.of(skillRef), List.of());
    verify(resolver).requireSubagentsForCreate("agent", List.of());

    AgentDefinition current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    when(repository.getByName("agent")).thenReturn(current);
    AgentDefinitionUpdateDTO update = update("0");
    update.getConfig().setSkills(List.of(skillRef));
    service.updateAgent("agent", update);
    verify(resolver, times(2)).requireReferencedLifecycles(List.of(skillRef), List.of());
  }

  /** 仓库写入失败：重复为 409、外键缺失为 404、非外键完整性错误原样抛出，CAS 落空重读后为 404 或版本冲突。 */
  @Test
  void classifiesFailedRepositoryMutations() {
    AgentDefinitionCreateDTO create = create("agent", "model", emptyConfig(), null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));
    when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));
    assertThrows(AiDuplicateException.class, () -> service.createAgent(create));
    doThrow(integrityFailure("23503")).when(repository).create(any());
    assertThrows(AiResourceNotFoundException.class, () -> service.createAgent(create));
    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(any());
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createAgent(create)));

    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", null));
    assertThrows(
        AiValidationException.class,
        () -> service.updateAgent("agent", new AgentDefinitionUpdateDTO()));
    AgentDefinition current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.updateAgent("agent", update("0")));
    when(repository.getByName("agent")).thenReturn(current.toBuilder().version(7L).build());
    assertThrows(AiVersionConflictException.class, () -> service.updateAgent("agent", update("0")));
    doThrow(integrityFailure("23503")).when(repository).updateByName(any(), eq(0L));
    assertThrows(
        AiResourceNotFoundException.class, () -> service.updateAgent("agent", update("0")));
    doThrow(nonForeignKey).when(repository).updateByName(any(), eq(0L));
    assertSame(
        nonForeignKey,
        assertThrows(
            DataIntegrityViolationException.class,
            () -> service.updateAgent("agent", update("0"))));
  }

  /** 版本过期在模型校验与改写之前确定性拒绝。 */
  @Test
  void staleVersionWinsOverModelValidation() {
    when(resolver.requireAgent("agent")).thenReturn(definition().toBuilder().version(1L).build());
    assertThrows(AiVersionConflictException.class, () -> service.updateAgent("agent", update("0")));
    verify(resolver, never()).requireModelForUpdate(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 删除 CAS：落空后重读区分 404 与版本冲突，使用解析后的 expectedVersion；过期版本在 CAS 前拒绝。 */
  @Test
  void deleteUsesCasAndRereadsOnFailure() {
    AgentDefinition current = definition().toBuilder().version(3L).build();
    when(resolver.requireAgentForUpdate("agent")).thenReturn(current);
    when(repository.deleteByName("agent", 3L)).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteAgent("agent", "3"));
    when(repository.getByName("agent")).thenReturn(current.toBuilder().version(7L).build());
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent("agent", "3"));

    when(repository.deleteByName("agent", 3L)).thenReturn(true);
    service.deleteAgent("agent", "3");
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent("agent", "0"));
    verify(repository, never()).deleteByName("agent", 0L);
  }

  /** 被 Project 工作流或插件配置引用的 Agent 拒绝删除，绝不触达真实删除。 */
  @Test
  void deleteRejectsReferencedAgents() {
    when(resolver.requireAgentForUpdate("agent")).thenReturn(definition());
    when(pluginAgentDependencies.pluginsDeclaringAgent("agent")).thenReturn(Set.of("acme.plugin"));
    AiInUseException plugin =
        assertThrows(AiInUseException.class, () -> service.deleteAgent("agent", "0"));
    assertTrue(plugin.getMessage().contains("referenced by plugin configuration"));

    when(repository.existsReferencingProjectWorkflow("agent")).thenReturn(true);
    AiInUseException workflow =
        assertThrows(AiInUseException.class, () -> service.deleteAgent("agent", "0"));
    assertTrue(workflow.getMessage().contains("project workflow stage"));
    verify(repository, never()).deleteByName(any(), anyLong());
  }

  /** 内置 Agent：名称保留不可同名创建、按类型受删除保护、可更新为显式未配置模型但不得带 variant。 */
  @Test
  void builtinAgentsKeepSystemOwnedIdentity() {
    AgentDefinitionCreateDTO reserved =
        create(BuiltinAgentDefinitions.COMPACTION_NAME, "model", emptyConfig(), null);
    assertThrows(AiDuplicateException.class, () -> service.createAgent(reserved));
    verify(repository, never()).create(any());

    AgentDefinition builtin =
        definition().toBuilder().type(AgentDefinitionType.BUILTIN).modelName(null).build();
    when(resolver.requireAgentForUpdate("agent")).thenReturn(builtin);
    assertThrows(AiInUseException.class, () -> service.deleteAgent("agent", "0"));
    verify(repository, never()).deleteByName(any(), anyLong());

    when(resolver.requireAgent("agent")).thenReturn(builtin);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(builtin);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    when(repository.getByName("agent")).thenReturn(builtin);
    AgentDefinitionUpdateDTO unconfigured = update("0");
    unconfigured.setModel(null);
    AgentDefinitionDTO dto = service.updateAgent("agent", unconfigured);
    assertNull(dto.getModel());
    verify(resolver, never()).requireModelForUpdate(any());
    verify(variants, never()).resolve(any(), any());

    unconfigured.setVariant("default");
    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", unconfigured));
  }

  /** 用户 Agent 的更新仍必须有模型（显式未配置只对内置 Agent 合法）。 */
  @Test
  void userAgentUpdateRequiresModel() {
    when(resolver.requireAgent("agent")).thenReturn(definition());
    AgentDefinitionUpdateDTO update = update("0");
    update.setModel(null);
    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", update));
    verify(resolver, never()).requireModelForUpdate(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** DTO 投影：model 为全局模型名，未配置模型时为 null。 */
  @Test
  void projectsDefinitionToDto() {
    AgentDefinitionDTO dto = service.toDto(definition());
    assertEquals("agent", dto.getName());
    assertEquals("model", dto.getModel());
    assertEquals("0", dto.getVersion());
    assertNull(service.toDto(definition().toBuilder().modelName(null).build()).getModel());
  }

  private AgentDefinition createAndCapture(AgentDefinitionCreateDTO create) {
    when(repository.create(any())).thenReturn(true);
    ArgumentCaptor<AgentDefinition> captor = ArgumentCaptor.forClass(AgentDefinition.class);
    when(repository.getByName(create.getName()))
        .thenAnswer(
            invocation -> {
              verify(repository, atLeastOnce()).create(captor.capture());
              return captor.getValue().toBuilder().version(0L).build();
            });
    service.createAgent(create);
    return captor.getValue();
  }

  private static AgentDefinition definition() {
    return AgentDefinition.builder()
        .name("agent")
        .type(AgentDefinitionType.USER)
        .modelName("model")
        .configJson(EMPTY_CONFIG)
        .version(0L)
        .build();
  }

  private static AgentModel model(String name, AgentModelKind kind) {
    return AgentModel.builder().name(name).kind(kind).version(0L).build();
  }

  private static AgentDefinitionCreateDTO create(
      String name, String model, AgentDefinitionConfigDTO config, String variant) {
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(name);
    create.setModel(model);
    create.setVariant(variant);
    create.setConfig(config);
    return create;
  }

  private static AgentDefinitionUpdateDTO update(String version) {
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setModel("model");
    update.setConfig(emptyConfig());
    update.setExpectedVersion(version);
    return update;
  }

  private static AgentDefinitionConfigDTO emptyConfig() {
    return config(List.of(), List.of());
  }

  private static AgentDefinitionConfigDTO config(List<String> tools, List<SkillRefDTO> skills) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(tools);
    config.setSkills(skills);
    config.setSubagents(List.of());
    return config;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
