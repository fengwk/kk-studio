package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 模型服务的输入规范化、可执行配置与组成员校验、CAS 失败分类与删除前置检查（仓库以 mock 隔离）。 */
class AgentModelServiceUnitTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final AgentModelRuntimeConfigParser parser =
      new AgentModelRuntimeConfigParser(objectMapper);
  private AgentModelRepository repository;
  private AgentProviderRepository providerRepository;
  private AgentModelService service;

  @BeforeEach
  void setUp() {
    repository = mock(AgentModelRepository.class);
    providerRepository = mock(AgentProviderRepository.class);
    when(providerRepository.getByNameForUpdate("provider"))
        .thenReturn(AgentProvider.builder().name("provider").build());
    service =
        new AgentModelService(
            repository,
            providerRepository,
            new AgentEditableSupport(objectMapper),
            parser,
            new AgentModelGroupConfigCodec(objectMapper));
  }

  /** 创建要求 kind、可执行配置与非空 Provider；成功时保持全局 name 与 modelId 身份。 */
  @Test
  void createRequiresExecutableConfigurationAndKeepsIdentity() {
    AgentModelCreateDTO create = create("model", null);
    create.setConfig(null);
    assertThrows(AiValidationException.class, () -> service.createModel(create));

    create.setConfig(
        AgentModelTestData.buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of(), "missing"));
    assertThrows(AiValidationException.class, () -> service.createModel(create));
    assertThrows(AiValidationException.class, () -> service.createModel(null));
    create.setConfig(validConfig());
    create.setProviderName(" ");
    assertThrows(AiValidationException.class, () -> service.createModel(create));
    AgentModelCreateDTO missingKind = create("model", null);
    missingKind.setKind(null);
    assertThrows(AiValidationException.class, () -> service.createModel(missingKind));
    AgentModelCreateDTO modelWithGroup = create("model", null);
    modelWithGroup.setGroup(group(member("member", "default")));
    assertThrows(AiValidationException.class, () -> service.createModel(modelWithGroup));

    AgentModel created = createAndCapture(create("model", null));
    assertEquals(AgentModelKind.MODEL, created.kind());
    assertEquals("provider", created.providerName());
    assertEquals("model", created.name());
    assertEquals("wire-model", created.modelId());
    assertNull(created.groupConfigJson());
  }

  /** Provider 不存在时 404，且不写入。 */
  @Test
  void createRejectsMissingProvider() {
    AgentModelCreateDTO create = create("model", null);
    create.setProviderName("ghost");
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));
    verify(repository, never()).create(any());
  }

  /** 编辑保留完整的基础费率定义。 */
  @Test
  void updatePreservesPricingDefinition() {
    AgentModel existing = existing("model", 0L);
    when(repository.getByNameForUpdate("model")).thenReturn(existing);
    when(repository.getByName("model")).thenReturn(existing);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    AgentModelConfigDTO config = validConfig();
    AgentModelRateSetDTO rates = new AgentModelRateSetDTO();
    rates.setInput(new BigDecimal("7"));
    rates.setOutput(new BigDecimal("9"));
    rates.setCacheRead(new BigDecimal("1"));
    rates.setCacheWrite(new BigDecimal("2"));
    rates.setCacheWriteByTtl(Map.of("300", new BigDecimal("3")));
    rates.setReasoning(new BigDecimal("4"));
    config.getPricing().setBase(rates);
    AgentModelUpdateDTO update = update("model", "0");
    update.setDescription("updated");
    update.setConfig(config);

    service.updateModel("model", update);
    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    verify(repository).updateByName(captor.capture(), eq(0L));

    assertEquals("updated", captor.getValue().description());
    AgentModelRateSetDTO persisted =
        parser.decode(captor.getValue().configJson()).getPricing().getBase();
    assertEquals(new BigDecimal("7"), persisted.getInput());
    assertEquals(new BigDecimal("9"), persisted.getOutput());
    assertEquals(new BigDecimal("1"), persisted.getCacheRead());
    assertEquals(new BigDecimal("2"), persisted.getCacheWrite());
    assertEquals(new BigDecimal("3"), persisted.getCacheWriteByTtl().get("300"));
    assertEquals(new BigDecimal("4"), persisted.getReasoning());
  }

  // 测试意图: name/modelId 仍受 schema 列宽约束，description 为 text 不拒绝长文本
  @Test
  void enforcesSchemaStringLimits() {
    AgentModel accepted = createAndCapture(create("n".repeat(128), "d".repeat(4096)));
    assertEquals("n".repeat(128), accepted.name());
    assertEquals("d".repeat(4096), accepted.description());

    assertThrows(AiValidationException.class, () -> service.createModel(create(" model ", null)));
    assertThrows(AiValidationException.class, () -> service.createModel(create(" model ", null)));
    assertThrows(
        AiValidationException.class, () -> service.createModel(create("n".repeat(129), null)));
    AgentModelCreateDTO oversizedModelId = create("model", null);
    oversizedModelId.setModelId("m".repeat(257));
    assertThrows(AiValidationException.class, () -> service.createModel(oversizedModelId));
  }

  /** 改名走 rename 并校验目标名；非法目标名与 kind 变化在写入前拒绝。 */
  @Test
  void renameValidatesTargetName() {
    AgentModel existing = existing("old-name", 0L);
    when(repository.getByNameForUpdate("old-name")).thenReturn(existing);
    when(repository.getByName("new-name"))
        .thenReturn(existing.toBuilder().name("new-name").version(1L).build());

    service.updateModel("old-name", update("new-name", "0"));
    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    verify(repository).rename(eq("old-name"), captor.capture(), eq(0L));
    assertEquals("new-name", captor.getValue().name());

    for (String invalid : List.of("   ", " padded-name ", "n".repeat(129))) {
      assertThrows(
          AiValidationException.class, () -> service.updateModel("old-name", update(invalid, "0")));
    }
    AgentModelUpdateDTO toGroup = new AgentModelUpdateDTO();
    toGroup.setName("old-name");
    toGroup.setKind(AgentModelKind.GROUP);
    toGroup.setGroup(group(member("member", "default")));
    toGroup.setExpectedVersion("0");
    AiValidationException error =
        assertThrows(AiValidationException.class, () -> service.updateModel("old-name", toGroup));
    assertEquals("model kind cannot be changed", error.getMessage());
  }

  /** 仍被 Agent 或模型组引用的 Variant 不能从 config 中移除。 */
  @Test
  void updateRejectsRemovingReferencedVariant() {
    AgentModel existing =
        existing("model", 0L).toBuilder()
            .configJson(
                parser.encode(
                    AgentModelTestData.buildConfig(
                        32768,
                        4096,
                        true,
                        false,
                        "standard",
                        "v1",
                        List.of("default", "fast"),
                        "default")))
            .build();
    when(repository.getByNameForUpdate("model")).thenReturn(existing);
    when(repository.hasVariantReferences("model", "fast")).thenReturn(true);

    AiInUseException error =
        assertThrows(
            AiInUseException.class, () -> service.updateModel("model", update("model", "0")));
    assertEquals("model variant is still referenced: model variant=fast", error.getMessage());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 仓库写入失败按原因分类：重复为 409、外键缺失为 404、CAS 落空重读后为 404 或版本冲突，非外键完整性错误原样抛出。 */
  @Test
  void classifiesFailedRepositoryMutations() {
    AgentModelCreateDTO create = create("model", null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createModel(create));
    doThrow(integrityFailure("23503")).when(repository).create(any());
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));
    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(any());
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createModel(create)));

    AgentModel model = existing("model", 0L);
    AgentModelUpdateDTO missing = new AgentModelUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateModel("model", missing));
    when(repository.getByNameForUpdate("model")).thenReturn(model);
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateModel("model", update("model", "0")));
    AgentModel reread = existing("model", 3L);
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class, () -> service.updateModel("model", update("model", "0")));
    when(repository.updateByName(any(), eq(0L))).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(
        AiDuplicateException.class, () -> service.updateModel("model", update("model", "0")));
    doThrow(new DuplicateKeyException("dup")).when(repository).rename(eq("model"), any(), eq(0L));
    assertThrows(
        AiDuplicateException.class,
        () -> service.updateModel("model", update("renamed-model", "0")));

    when(repository.deleteByName(eq("model"), anyLong())).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteModel("model", "0"));
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel("model", "0"));
    verify(repository, atLeastOnce()).getByNameForUpdate("model");
    when(repository.getByNameForUpdate("ghost")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteModel("ghost", "0"));
  }

  /** 版本过期优先于删除引用检查；仍被 Agent 或模型组引用的模型拒绝删除。 */
  @Test
  void staleVersionWinsOverDeletionChecks() {
    when(repository.getByNameForUpdate("model")).thenReturn(existing("model", 1L));
    when(repository.hasAgents("model")).thenReturn(true);

    assertThrows(
        AiVersionConflictException.class, () -> service.updateModel("model", update("model", "0")));
    verify(repository, never()).updateByName(any(), anyLong());
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel("model", "0"));
    verify(repository, never()).hasAgents("model");

    assertThrows(AiInUseException.class, () -> service.deleteModel("model", "1"));
    when(repository.hasAgents("model")).thenReturn(false);
    when(repository.hasGroupsReferencing("model")).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.deleteModel("model", "1"));
    verify(repository, never()).deleteByName(any(), anyLong());
  }

  /** 测试意图：组只接受已存在单 Model 声明的 Variant，不能嵌套组，成员输入模态必须有交集，且不携带 MODEL 字段。 */
  @Test
  void validatesGroupMembersAndDerivedModalities() {
    stubMember(existing("member", 0L));

    AgentModel created = createAndCapture(groupCreate("group", member("member", "default")));
    assertEquals(AgentModelKind.GROUP, created.kind());
    assertNull(created.providerName());
    assertNull(created.configJson());
    assertNotNull(created.groupConfigJson());

    when(repository.getByNameForUpdate("group")).thenReturn(created);
    assertThrows(
        AiValidationException.class,
        () -> service.createModel(groupCreate("nested", member("group", "default"))));
    assertThrows(
        AiValidationException.class,
        () -> service.createModel(groupCreate("missing-variant", member("member", "absent"))));
    assertThrows(
        AiValidationException.class,
        () -> service.createModel(groupCreate("unknown", member("ghost", "default"))));
    AgentModelCreateDTO groupWithProvider =
        groupCreate("with-provider", member("member", "default"));
    groupWithProvider.setProviderName("provider");
    assertThrows(AiValidationException.class, () -> service.createModel(groupWithProvider));

    AgentModelConfigDTO imageConfig = validConfig();
    imageConfig.getAbilities().setInputModalities(List.of(AgentModelInputModality.IMAGE));
    AgentModel imageOnly =
        existing("image-member", 0L).toBuilder().configJson(parser.encode(imageConfig)).build();
    when(repository.getByNameForUpdate("image-member")).thenReturn(imageOnly);
    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                service.createModel(
                    groupCreate(
                        "no-intersection",
                        member("member", "default"),
                        member("image-member", "default"))));
    assertEquals("group.members have no common input modality", error.getMessage());
  }

  /** 组的 DTO 从成员派生只读能力摘要；配置缺失的行无法投影为 DTO，校验失败而不是返回半个模型。 */
  @Test
  void dtoRequiresStoredConfig() {
    assertThrows(
        AiValidationException.class,
        () -> service.toDto(existing("model", 0L).toBuilder().configJson(" ").build()));
    assertEquals("model", service.toDto(existing("model", 0L)).getName());

    stubMember(existing("member", 0L));
    AgentModel group = createAndCapture(groupCreate("group", member("member", "default")));
    AgentModelDTO dto = service.toDto(group.toBuilder().version(0L).build());
    assertEquals(AgentModelKind.GROUP, dto.getKind());
    assertEquals(32768, dto.getConfig().getLimit().getContext());
    assertEquals("member", dto.getGroup().getMembers().getFirst().getModelName());
    assertThrows(
        AiValidationException.class,
        () -> service.toDto(group.toBuilder().groupConfigJson(null).version(0L).build()));
  }

  private AgentModel createAndCapture(AgentModelCreateDTO create) {
    when(repository.create(any())).thenReturn(true);
    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    when(repository.getByName(create.getName()))
        .thenAnswer(
            invocation -> {
              verify(repository, atLeastOnce()).create(captor.capture());
              return captor.getValue().toBuilder().version(0L).build();
            });
    service.createModel(create);
    return captor.getValue();
  }

  /** 组写入锁定并校验成员行，组 DTO 从成员行派生能力摘要。 */
  private void stubMember(AgentModel member) {
    when(repository.getByNameForUpdate(member.name())).thenReturn(member);
    when(repository.getByName(member.name())).thenReturn(member);
  }

  private AgentModel existing(String name, long version) {
    return AgentModel.builder()
        .name(name)
        .kind(AgentModelKind.MODEL)
        .providerName("provider")
        .modelId("wire-model")
        .configJson(parser.encode(validConfig()))
        .version(version)
        .build();
  }

  private static AgentModelConfigDTO validConfig() {
    return AgentModelTestData.buildConfig(
        32768, 4096, true, false, "standard", "v1", List.of("default"), "default");
  }

  private static AgentModelCreateDTO create(String name, String description) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setKind(AgentModelKind.MODEL);
    create.setProviderName("provider");
    create.setModelId("wire-model");
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private static AgentModelUpdateDTO update(String name, String expectedVersion) {
    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setName(name);
    update.setKind(AgentModelKind.MODEL);
    update.setProviderName("provider");
    update.setModelId("wire-model");
    update.setConfig(validConfig());
    update.setExpectedVersion(expectedVersion);
    return update;
  }

  private static AgentModelCreateDTO groupCreate(String name, AgentModelGroupMemberDTO... members) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setKind(AgentModelKind.GROUP);
    create.setGroup(group(members));
    return create;
  }

  private static AgentModelGroupDTO group(AgentModelGroupMemberDTO... members) {
    AgentModelGroupDTO group = new AgentModelGroupDTO();
    group.setMembers(List.of(members));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(0);
    return group;
  }

  private static AgentModelGroupMemberDTO member(String modelName, String variant) {
    AgentModelGroupMemberDTO member = new AgentModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant(variant);
    return member;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
