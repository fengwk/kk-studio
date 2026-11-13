package fun.fengwk.kkstudio.platform.catalog.definition;

import static fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent 定义服务的单元契约（仓库与引用解析以 mock 隔离）：字段与配置规范化、内置 Agent 规则、CAS 失败分类、完整性错误映射与删除前置检查。 */
class AgentServiceUnitTest {

  private static final String EMPTY_CONFIG = "{\"tools\":[],\"skills\":[],\"subagents\":[]}";

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private AgentRepository repository;
  private AgentReferenceResolver resolver;
  private ModelRepository modelRepository;
  private ModelDefaultVariantResolver variants;
  private PluginAgentDependencies pluginAgentDependencies;
  private AgentReferenceCheck productReference;
  private AgentReferenceCheck otherProductReference;
  private AgentService service;

  @BeforeEach
  void setUp() {
    repository = mock(AgentRepository.class);
    resolver = mock(AgentReferenceResolver.class);
    modelRepository = mock(ModelRepository.class);
    variants = mock(ModelDefaultVariantResolver.class);
    pluginAgentDependencies = mock(PluginAgentDependencies.class);
    productReference = mock(AgentReferenceCheck.class);
    otherProductReference = mock(AgentReferenceCheck.class);
    when(modelRepository.getByNameForUpdate(any()))
        .thenAnswer(invocation -> model(invocation.getArgument(0), ModelKind.MODEL));
    AgentDriver builtin = mock(AgentDriver.class);
    when(builtin.id()).thenReturn(BuiltinExecution.DRIVER);
    doAnswer(
            invocation -> {
              BuiltinExecution.parseSettings(invocation.getArgument(0));
              return null;
            })
        .when(builtin)
        .validateSettings(any());
    AgentDriver fake = mock(AgentDriver.class);
    when(fake.id()).thenReturn("fake");
    doAnswer(
            invocation -> {
              if (!"{\"endpoint\":\"local\"}".equals(invocation.getArgument(0))) {
                throw new IllegalArgumentException("invalid fake settings");
              }
              return null;
            })
        .when(fake)
        .validateSettings(any());
    service =
        new AgentService(
            repository,
            resolver,
            new DriverRegistry(builtin, fake),
            new BuiltinAgentExecutionValidator(modelRepository, variants),
            new AgentConfigValidator(
                TestRuntimeToolCatalogSource.of(HarnessCatalog.from(List.of()))),
            new AgentConfigCodec(objectMapper),
            new AgentEditableSupport(objectMapper),
            pluginAgentDependencies,
            List.of(productReference, otherProductReference));
  }

  /** 创建持久化规范化的能力列表与全局 model 名；更新保持名称身份并改写模型与 variant。 */
  @Test
  void persistsCanonicalConfigurationAndKeepsIdentity() throws Exception {
    Agent created =
        createAndCapture(
            create(
                "agent",
                "model",
                config(List.of(), List.of(skillRef("tools", "java"), skillRef("tools", "dev"))),
                null));
    AgentConfigDTO stored = objectMapper.readValue(created.configJson(), AgentConfigDTO.class);
    assertEquals(List.of(skillRef("tools", "java"), skillRef("tools", "dev")), stored.getSkills());
    assertEquals(ModelSelection.model("model", null), BuiltinExecution.model(created.execution()));

    Agent current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    when(repository.getByName("agent")).thenReturn(current);
    AgentUpdateDTO update = update(0L);
    update.setDescription("updated");
    update.setExecution(execution("vendor/other model", "quality"));
    service.updateAgent("agent", update);

    ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
    verify(repository).updateByName(captor.capture(), eq(0L));
    Agent updated = captor.getValue();
    assertEquals("agent", updated.name());
    assertEquals("updated", updated.description());
    assertEquals(
        ModelSelection.model("vendor/other model", "quality"),
        BuiltinExecution.model(updated.execution()));
    verify(modelRepository).getByNameForUpdate("vendor/other model");
    verify(variants).resolve("vendor/other model", "quality");
  }

  /** 非 canonical 能力名、缺字段与超限输入在写入前确定性拒绝；description 为 text 不截断。 */
  @Test
  void rejectsNonCanonicalOrIncompleteInput() {
    DomainException duplicate =
        assertThrows(
            DomainException.class,
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
        "agent config skills must not contain duplicates: tools/java", duplicate.getMessage());
    DomainException tool =
        assertThrows(
            DomainException.class,
            () ->
                service.validateImport(
                    "agent", create("agent", "model", config(List.of(" read "), List.of()), null)));
    assertEquals(
        "agent config tools must contain valid model-visible tool names:  read ",
        tool.getMessage());

    for (String invalidName : List.of(" ", " agent ", "n".repeat(65), "agent/name")) {
      assertThrows(
          DomainException.class,
          () ->
              service.validateImport(
                  invalidName, create(invalidName, "model", emptyConfig(), null)));
    }
    assertThrows(DomainException.class, () -> service.validateImport("agent", null));
    assertThrows(
        DomainException.class,
        () -> service.validateImport(null, create(null, "model", emptyConfig(), null)));
    assertThrows(
        DomainException.class,
        () -> service.validateImport("agent", create("agent", null, emptyConfig(), null)));
    for (String invalidModel : List.of(" model ", " model", "model ", "m".repeat(129))) {
      assertThrows(
          DomainException.class,
          () ->
              service.validateImport("agent", create("agent", invalidModel, emptyConfig(), null)));
    }
    assertThrows(
        DomainException.class,
        () -> service.validateImport("agent", create("agent", "model", null, null)));

    AgentCreateDTO longDescription = create("agent", "model", emptyConfig(), null);
    longDescription.setDescription("d".repeat(4096));
    Agent created = createAndCapture(longDescription);
    assertEquals("d".repeat(4096), created.description());
    assertNull(BuiltinExecution.model(created.execution()).variant());
  }

  /** 非法 model 名、variant、组级 variant 或执行配置在写入前包装为校验错误；null 请求同样确定性拒绝。 */
  @Test
  void wrapsInvalidReferencesAsValidationErrors() {
    AgentCreateDTO badRef = create("agent", " model", emptyConfig(), null);
    assertThrows(DomainException.class, () -> service.createAgent(badRef));
    assertThrows(DomainException.class, () -> service.createAgent(null));

    when(variants.resolve("model", "bad-variant"))
        .thenThrow(new IllegalArgumentException("unknown variant: bad-variant"));
    assertThrows(
        DomainException.class,
        () -> service.createAgent(create("agent", "model", emptyConfig(), "bad-variant")));

    when(modelRepository.getByNameForUpdate("group")).thenReturn(model("group", ModelKind.GROUP));
    DomainException groupVariant =
        assertThrows(
            DomainException.class,
            () -> service.createAgent(create("agent", "group", emptyConfig(), "default")));
    assertEquals(
        "variant must be blank when the selected model is a GROUP", groupVariant.getMessage());
    verify(variants, never()).resolve(eq("group"), any());

    assertThrows(
        DomainException.class,
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

    Agent current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    when(repository.getByName("agent")).thenReturn(current);
    AgentUpdateDTO update = update(0L);
    update.getConfig().setSkills(List.of(skillRef));
    service.updateAgent("agent", update);
    verify(resolver, times(2)).requireReferencedLifecycles(List.of(skillRef), List.of());
  }

  /** 仓库写入失败：重复为 409、完整性错误原样抛出，CAS 落空重读后为 404 或版本冲突。 */
  @Test
  void classifiesFailedRepositoryMutations() {
    AgentCreateDTO create = create("agent", "model", emptyConfig(), null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));
    when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));
    assertThrows(DomainException.class, () -> service.createAgent(create));
    DataIntegrityViolationException foreignKey = integrityFailure("23503");
    doThrow(foreignKey).when(repository).create(any());
    assertSame(
        foreignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createAgent(create)));
    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(any());
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createAgent(create)));

    assertThrows(DomainException.class, () -> service.updateAgent("agent", null));
    assertThrows(DomainException.class, () -> service.updateAgent("agent", new AgentUpdateDTO()));
    Agent current = definition();
    when(resolver.requireAgent("agent")).thenReturn(current);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.updateAgent("agent", update(0L)));
    when(repository.getByName("agent")).thenReturn(current.toBuilder().version(7L).build());
    assertThrows(DomainException.class, () -> service.updateAgent("agent", update(0L)));
    doThrow(foreignKey).when(repository).updateByName(any(), eq(0L));
    assertSame(
        foreignKey,
        assertThrows(
            DataIntegrityViolationException.class, () -> service.updateAgent("agent", update(0L))));
    doThrow(nonForeignKey).when(repository).updateByName(any(), eq(0L));
    assertSame(
        nonForeignKey,
        assertThrows(
            DataIntegrityViolationException.class, () -> service.updateAgent("agent", update(0L))));
  }

  /** 版本过期在模型校验与改写之前确定性拒绝。 */
  @Test
  void staleVersionWinsOverModelValidation() {
    when(resolver.requireAgent("agent")).thenReturn(definition().toBuilder().version(1L).build());
    assertThrows(DomainException.class, () -> service.updateAgent("agent", update(0L)));
    verify(modelRepository, never()).getByNameForUpdate(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 删除 CAS：落空后重读区分 404 与版本冲突，使用解析后的 expectedVersion；过期版本在 CAS 前拒绝。 */
  @Test
  void deleteUsesCasAndRereadsOnFailure() {
    Agent current = definition().toBuilder().version(3L).build();
    when(resolver.requireAgentForUpdate("agent")).thenReturn(current);
    when(repository.deleteByName("agent", 3L)).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.deleteAgent("agent", 3L));
    when(repository.getByName("agent")).thenReturn(current.toBuilder().version(7L).build());
    assertThrows(DomainException.class, () -> service.deleteAgent("agent", 3L));

    when(repository.deleteByName("agent", 3L)).thenReturn(true);
    service.deleteAgent("agent", 3L);
    assertThrows(DomainException.class, () -> service.deleteAgent("agent", 0L));
    verify(repository, never()).deleteByName("agent", 0L);
  }

  /** 任一产品或插件配置引用 Agent 时拒绝删除，绝不触达真实删除。 */
  @Test
  void deleteRejectsReferencedAgents() {
    when(resolver.requireAgentForUpdate("agent")).thenReturn(definition());
    when(pluginAgentDependencies.pluginsDeclaringAgent("agent")).thenReturn(Set.of("acme.plugin"));
    DomainException plugin =
        assertThrows(DomainException.class, () -> service.deleteAgent("agent", 0L));
    assertTrue(plugin.getMessage().contains("referenced by plugin configuration"));

    when(otherProductReference.isReferenced("agent")).thenReturn(true);
    DomainException product =
        assertThrows(DomainException.class, () -> service.deleteAgent("agent", 0L));
    assertTrue(product.getMessage().contains("referenced by a product"));
    verify(productReference, atLeastOnce()).isReferenced("agent");
    verify(otherProductReference, atLeastOnce()).isReferenced("agent");
    verify(repository, never()).deleteByName(any(), anyLong());
  }

  /** 更新必须携带 execution。 */
  @Test
  void userAgentUpdateRequiresModel() {
    when(resolver.requireAgent("agent")).thenReturn(definition());
    AgentUpdateDTO update = update(0L);
    update.setExecution(null);
    assertThrows(DomainException.class, () -> service.updateAgent("agent", update));
    verify(modelRepository, never()).getByNameForUpdate(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** DTO 投影包含完整的 builtin 设置；未配置 execution 时为 null。 */
  @Test
  void projectsDefinitionToDto() {
    AgentDTO dto = service.toDto(definition());
    assertEquals("agent", dto.getName());
    assertEquals(execution("model", null), dto.getExecution());
    assertEquals(0L, dto.getVersion());
    assertNull(service.toDto(definition().toBuilder().execution(null).build()).getExecution());
  }

  /** 已安装的非 builtin driver 使用自己的设置，并完整往返，不读取模型目录。 */
  @Test
  void acceptsInstalledNonBuiltinDriver() {
    AgentCreateDTO create = create("agent", "model", emptyConfig(), null);
    HarnessExecutionSelectionDTO selection = new HarnessExecutionSelectionDTO();
    selection.setDriver("fake");
    selection.setSettings(jsonObject(Map.of("endpoint", "local")));
    create.setExecution(selection);

    Agent stored = createAndCapture(create);
    assertEquals("fake", stored.execution().driver());
    assertEquals("{\"endpoint\":\"local\"}", stored.execution().settingsJson());
    assertEquals(selection, service.toDto(stored.toBuilder().version(0L).build()).getExecution());
    verify(modelRepository, never()).getByNameForUpdate(any());
    verify(variants, never()).resolve(any(), any());
  }

  /** 未安装 driver、非法 driver 名及各 driver 的非法设置都在写入前拒绝。 */
  @Test
  void rejectsUninstalledAndMalformedDrivers() {
    AgentCreateDTO create = create("agent", "model", emptyConfig(), null);
    HarnessExecutionSelectionDTO selection = new HarnessExecutionSelectionDTO();
    selection.setDriver("missing");
    selection.setSettings(jsonObject(Map.of("endpoint", "local")));
    create.setExecution(selection);
    DomainException missing =
        assertThrows(DomainException.class, () -> service.createAgent(create));
    assertTrue(missing.getMessage().contains("driver is not installed: missing"));

    selection.setDriver("Bad Driver");
    assertThrows(DomainException.class, () -> service.createAgent(create));
    selection.setDriver(null);
    assertThrows(DomainException.class, () -> service.createAgent(create));
    selection.setDriver("fake");
    selection.setSettings(null);
    assertThrows(DomainException.class, () -> service.createAgent(create));
    selection.setSettings(jsonObject(Map.of("endpoint", "other")));
    DomainException invalidSettings =
        assertThrows(DomainException.class, () -> service.createAgent(create));
    assertTrue(invalidSettings.getMessage().contains("invalid fake settings"));

    HarnessExecutionSelectionDTO malformedBuiltin = execution("model", null);
    ObjectNode malformedBuiltinSettings = (ObjectNode) malformedBuiltin.getSettings();
    malformedBuiltinSettings.remove("variant");
    create.setExecution(malformedBuiltin);
    assertThrows(DomainException.class, () -> service.createAgent(create));
    malformedBuiltinSettings.putNull("variant");
    malformedBuiltinSettings.put("unexpected", true);
    assertThrows(DomainException.class, () -> service.createAgent(create));
    verify(repository, never()).create(any());
    verify(modelRepository, never()).getByNameForUpdate(any());
  }

  /** Builtin Variant 的目录列宽在写入和导入预检时均受约束。 */
  @Test
  void rejectsOversizedBuiltinVariant() {
    AgentCreateDTO create = create("agent", "model", emptyConfig(), "v".repeat(65));
    assertThrows(DomainException.class, () -> service.createAgent(create));
    assertThrows(DomainException.class, () -> service.validateImport("agent", create));
    verify(repository, never()).create(any());
  }

  private Agent createAndCapture(AgentCreateDTO create) {
    when(repository.create(any())).thenReturn(true);
    ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
    when(repository.getByName(create.getName()))
        .thenAnswer(
            invocation -> {
              verify(repository, atLeastOnce()).create(captor.capture());
              return captor.getValue().toBuilder().version(0L).build();
            });
    service.createAgent(create);
    return captor.getValue();
  }

  private static Agent definition() {
    return Agent.builder()
        .name("agent")
        .execution(BuiltinExecution.of(ModelSelection.model("model", null)))
        .configJson(EMPTY_CONFIG)
        .version(0L)
        .build();
  }

  private static Model model(String name, ModelKind kind) {
    return Model.builder().name(name).kind(kind).version(0L).build();
  }

  private static AgentCreateDTO create(
      String name, String model, AgentConfigDTO config, String variant) {
    AgentCreateDTO create = new AgentCreateDTO();
    create.setName(name);
    create.setExecution(model == null ? null : execution(model, variant));
    create.setConfig(config);
    return create;
  }

  private static AgentUpdateDTO update(long version) {
    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setExecution(execution("model", null));
    update.setConfig(emptyConfig());
    update.setExpectedVersion(version);
    return update;
  }

  private static AgentConfigDTO emptyConfig() {
    return config(List.of(), List.of());
  }

  private static HarnessExecutionSelectionDTO execution(String model, String variant) {
    ObjectNode settings = jsonObject();
    settings.put("modelName", model);
    if (variant == null) {
      settings.putNull("variant");
    } else {
      settings.put("variant", variant);
    }
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver(BuiltinExecution.DRIVER);
    execution.setSettings(settings);
    return execution;
  }

  private static AgentConfigDTO config(List<String> tools, List<SkillRefDTO> skills) {
    AgentConfigDTO config = new AgentConfigDTO();
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
