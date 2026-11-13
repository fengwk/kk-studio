package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 模型服务的输入规范化、可执行配置与组成员校验、CAS 失败分类与删除前置检查（仓库以 mock 隔离）。 */
class ModelServiceUnitTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final ModelRuntimeConfigParser parser = new ModelRuntimeConfigParser(objectMapper);
  private ModelRepository repository;
  private ProviderRepository providerRepository;
  private ModelService service;

  @BeforeEach
  void setUp() {
    repository = mock(ModelRepository.class);
    providerRepository = mock(ProviderRepository.class);
    when(providerRepository.getByNameForUpdate("provider"))
        .thenReturn(Provider.builder().name("provider").providerType(ProviderType.OPENAI).build());
    service =
        new ModelService(
            repository,
            providerRepository,
            new AgentEditableSupport(objectMapper),
            parser,
            new ModelGroupConfigCodec(objectMapper));
  }

  /** 创建要求 kind、可执行配置与非空 Provider；成功时保持全局 name 与 modelId 身份。 */
  @Test
  void createRequiresExecutableConfigurationAndKeepsIdentity() {
    SingleModelCreateDTO create = create("model", null);
    create.setConfig(null);
    assertThrows(DomainException.class, () -> service.createModel(create));

    create.setConfig(
        ModelTestData.buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of(), "missing"));
    assertThrows(DomainException.class, () -> service.createModel(create));
    assertThrows(DomainException.class, () -> service.createModel(null));
    create.setConfig(validConfig());
    create.setProviderName(" ");
    assertThrows(DomainException.class, () -> service.createModel(create));
    // MODEL/GROUP 的类型由联合子类型决定，不再存在可被篡改的 kind 字段。
    Model created = createAndCapture(create("model", null));
    assertEquals(ModelKind.MODEL, created.kind());
    assertEquals("provider", created.providerName());
    assertEquals("model", created.name());
    assertEquals("wire-model", created.modelId());
    assertNull(created.groupConfigJson());
  }

  /** Provider 不存在时 404，且不写入。 */
  @Test
  void createRejectsMissingProvider() {
    SingleModelCreateDTO create = create("model", null);
    create.setProviderName("ghost");
    assertThrows(DomainException.class, () -> service.createModel(create));
    verify(repository, never()).create(any());
  }

  /** 编辑保留完整的基础费率定义。 */
  @Test
  void updatePreservesPricingDefinition() {
    Model existing = existing("model", 0L);
    when(repository.getByNameForUpdate("model")).thenReturn(existing);
    when(repository.getByName("model")).thenReturn(existing);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    ModelConfigDTO config = validConfig();
    ModelRateSetDTO rates = new ModelRateSetDTO();
    rates.setInput(new BigDecimal("7"));
    rates.setOutput(new BigDecimal("9"));
    rates.setCacheRead(new BigDecimal("1"));
    rates.setCacheWrite(new BigDecimal("2"));
    rates.setCacheWriteByTtl(Map.of("300", new BigDecimal("3")));
    rates.setReasoning(new BigDecimal("4"));
    config.getPricing().setBase(rates);
    SingleModelUpdateDTO update = update("model", 0L);
    update.setDescription("updated");
    update.setConfig(config);

    service.updateModel("model", update);
    ArgumentCaptor<Model> captor = ArgumentCaptor.forClass(Model.class);
    verify(repository).updateByName(captor.capture(), eq(0L));

    assertEquals("updated", captor.getValue().description());
    ModelRateSetDTO persisted =
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
    Model accepted = createAndCapture(create("n".repeat(128), "d".repeat(4096)));
    assertEquals("n".repeat(128), accepted.name());
    assertEquals("d".repeat(4096), accepted.description());

    assertThrows(DomainException.class, () -> service.createModel(create(" model ", null)));
    assertThrows(DomainException.class, () -> service.createModel(create(" model ", null)));
    assertThrows(DomainException.class, () -> service.createModel(create("n".repeat(129), null)));
    SingleModelCreateDTO oversizedModelId = create("model", null);
    oversizedModelId.setModelId("m".repeat(257));
    assertThrows(DomainException.class, () -> service.createModel(oversizedModelId));
  }

  /** 改名走 rename 并校验目标名；非法目标名与 kind 变化在写入前拒绝。 */
  @Test
  void renameValidatesTargetName() {
    Model existing = existing("old-name", 0L);
    when(repository.getByNameForUpdate("old-name")).thenReturn(existing);
    when(repository.getByName("new-name"))
        .thenReturn(existing.toBuilder().name("new-name").version(1L).build());

    service.updateModel("old-name", update("new-name", 0L));
    ArgumentCaptor<Model> captor = ArgumentCaptor.forClass(Model.class);
    verify(repository).rename(eq("old-name"), captor.capture(), eq(0L));
    assertEquals("new-name", captor.getValue().name());

    for (String invalid : List.of("   ", " padded-name ", "n".repeat(129))) {
      assertThrows(
          DomainException.class, () -> service.updateModel("old-name", update(invalid, 0L)));
    }
    ModelGroupUpdateDTO toGroup = new ModelGroupUpdateDTO();
    toGroup.setName("old-name");
    toGroup.setGroup(group(member("member", "default")));
    toGroup.setExpectedVersion(0L);
    DomainException error =
        assertThrows(DomainException.class, () -> service.updateModel("old-name", toGroup));
    assertEquals("model type cannot be changed", error.getMessage());
  }

  /** 仍被 Agent 或模型组引用的 Variant 不能从 config 中移除。 */
  @Test
  void updateRejectsRemovingReferencedVariant() {
    Model existing =
        existing("model", 0L).toBuilder()
            .configJson(
                parser.encode(
                    ModelTestData.buildConfig(
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

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.updateModel("model", update("model", 0L)));
    assertEquals("model variant is still referenced: model variant=fast", error.getMessage());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 仓库写入失败按原因分类：重复为 409、外键缺失为 404、CAS 落空重读后为 404 或版本冲突，非外键完整性错误原样抛出。 */
  @Test
  void classifiesFailedRepositoryMutations() {
    ModelCreateDTO create = create("model", null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(DomainException.class, () -> service.createModel(create));
    doThrow(integrityFailure("23503")).when(repository).create(any());
    assertThrows(DomainException.class, () -> service.createModel(create));
    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(any());
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createModel(create)));

    Model model = existing("model", 0L);
    SingleModelUpdateDTO missing = new SingleModelUpdateDTO();
    assertThrows(DomainException.class, () -> service.updateModel("model", missing));
    when(repository.getByNameForUpdate("model")).thenReturn(model);
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.updateModel("model", update("model", 0L)));
    Model reread = existing("model", 3L);
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(DomainException.class, () -> service.updateModel("model", update("model", 0L)));
    when(repository.updateByName(any(), eq(0L))).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(DomainException.class, () -> service.updateModel("model", update("model", 0L)));
    doThrow(new DuplicateKeyException("dup")).when(repository).rename(eq("model"), any(), eq(0L));
    assertThrows(
        DomainException.class, () -> service.updateModel("model", update("renamed-model", 0L)));

    when(repository.deleteByName(eq("model"), anyLong())).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.deleteModel("model", 0L));
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(DomainException.class, () -> service.deleteModel("model", 0L));
    verify(repository, atLeastOnce()).getByNameForUpdate("model");
    when(repository.getByNameForUpdate("ghost")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.deleteModel("ghost", 0L));
  }

  /** 版本过期优先于删除引用检查；仍被 Agent 或模型组引用的模型拒绝删除。 */
  @Test
  void staleVersionWinsOverDeletionChecks() {
    when(repository.getByNameForUpdate("model")).thenReturn(existing("model", 1L));
    when(repository.hasAgents("model")).thenReturn(true);

    assertThrows(DomainException.class, () -> service.updateModel("model", update("model", 0L)));
    verify(repository, never()).updateByName(any(), anyLong());
    assertThrows(DomainException.class, () -> service.deleteModel("model", 0L));
    verify(repository, never()).hasAgents("model");

    assertThrows(DomainException.class, () -> service.deleteModel("model", 1L));
    when(repository.hasAgents("model")).thenReturn(false);
    when(repository.hasGroupsReferencing("model")).thenReturn(true);
    assertThrows(DomainException.class, () -> service.deleteModel("model", 1L));
    verify(repository, never()).deleteByName(any(), anyLong());
  }

  /** 测试意图：组只接受已存在单 Model 声明的 Variant，不能嵌套组，成员输入模态必须有交集，且不携带 MODEL 字段。 */
  @Test
  void validatesGroupMembersAndDerivedModalities() {
    stubMember(existing("member", 0L));

    Model created = createAndCapture(groupCreate("group", member("member", "default")));
    assertEquals(ModelKind.GROUP, created.kind());
    assertNull(created.providerName());
    assertNull(created.configJson());
    assertNotNull(created.groupConfigJson());

    when(repository.getByNameForUpdate("group")).thenReturn(created);
    assertThrows(
        DomainException.class,
        () -> service.createModel(groupCreate("nested", member("group", "default"))));
    assertThrows(
        DomainException.class,
        () -> service.createModel(groupCreate("missing-variant", member("member", "absent"))));
    assertThrows(
        DomainException.class,
        () -> service.createModel(groupCreate("unknown", member("ghost", "default"))));
    ModelConfigDTO imageConfig = validConfig();
    imageConfig.getAbilities().setInputModalities(List.of(ModelInputModality.IMAGE));
    Model imageOnly =
        existing("image-member", 0L).toBuilder().configJson(parser.encode(imageConfig)).build();
    when(repository.getByNameForUpdate("image-member")).thenReturn(imageOnly);
    DomainException error =
        assertThrows(
            DomainException.class,
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
        DomainException.class,
        () -> service.toDto(existing("model", 0L).toBuilder().configJson(" ").build()));
    assertEquals("model", service.toDto(existing("model", 0L)).getName());

    stubMember(existing("member", 0L));
    Model group = createAndCapture(groupCreate("group", member("member", "default")));
    ModelDTO dto = service.toDto(group.toBuilder().version(0L).build());
    ModelGroupDTO groupDto = (ModelGroupDTO) dto;
    assertEquals(32768, groupDto.getConfig().getLimit().getContext());
    assertEquals("member", groupDto.getGroup().getMembers().getFirst().getModelName());
    assertThrows(
        DomainException.class,
        () -> service.toDto(group.toBuilder().groupConfigJson(null).version(0L).build()));
  }

  /** 组能力摘要只在每个成员都支持时才声明该能力。 */
  @Test
  void derivesGroupBooleanCapabilitiesByIntersection() {
    ModelConfigDTO capable = validConfig();
    capable.getAbilities().setMidConversationSystemMessages(true);
    capable.getAbilities().setMessageLevelEffort(true);
    stubMember(existing("capable-a", 0L).toBuilder().configJson(parser.encode(capable)).build());
    stubMember(existing("not-capable", 0L));

    Model group =
        createAndCapture(
            groupCreate(
                "partial-capability",
                member("capable-a", "default"),
                member("not-capable", "default")));
    ModelGroupDTO partialDto = (ModelGroupDTO) service.toDto(group.toBuilder().version(0L).build());
    assertFalse(partialDto.getConfig().getAbilities().isMidConversationSystemMessages());
    assertFalse(partialDto.getConfig().getAbilities().isMessageLevelEffort());

    stubMember(existing("capable-b", 0L).toBuilder().configJson(parser.encode(capable)).build());
    Model allCapableGroup =
        createAndCapture(
            groupCreate(
                "all-capable", member("capable-a", "default"), member("capable-b", "default")));
    ModelGroupDTO allCapableDto =
        (ModelGroupDTO) service.toDto(allCapableGroup.toBuilder().version(0L).build());
    assertTrue(allCapableDto.getConfig().getAbilities().isMidConversationSystemMessages());
    assertTrue(allCapableDto.getConfig().getAbilities().isMessageLevelEffort());
  }

  /** 组输入上限按声明成员取最小值：无成员声明保持缺省，最小值不小于派生上下文时不产出无意义的上限。 */
  @Test
  void derivesGroupInputLimitFromDeclaredMembersOnly() {
    // 无成员声明 limit.input：派生 limit 不带 input。
    stubMember(existing("plain-a", 0L));
    stubMember(existing("plain-b", 0L));
    Model noInput =
        createAndCapture(
            groupCreate("no-input", member("plain-a", "default"), member("plain-b", "default")));
    assertNull(derivedLimit(noInput).getInput());

    // 只有部分成员声明：取声明成员的最小值。
    ModelConfigDTO declared = declaredWithInput(10000);
    stubMember(existing("input-10k", 0L).toBuilder().configJson(parser.encode(declared)).build());
    stubMember(
        existing("input-20k", 0L).toBuilder()
            .configJson(parser.encode(declaredWithInput(20000)))
            .build());
    Model partial =
        createAndCapture(
            groupCreate(
                "partial-input", member("input-10k", "default"), member("input-20k", "default")));
    assertEquals(10000, derivedLimit(partial).getInput());

    // 声明成员的最小值不小于派生 context：保持缺省。
    // 派生 context 取成员最小 4096，声明的 input 最小值也是 4096，上限相对派生窗口无意义。
    ModelConfigDTO tighterContext = validConfig();
    tighterContext.getLimit().setContext(4096);
    tighterContext.getLimit().setOutput(4096);
    tighterContext.getLimit().setInput(4096);
    stubMember(
        existing("small-ctx", 0L).toBuilder().configJson(parser.encode(tighterContext)).build());
    stubMember(
        existing("wide-input", 0L).toBuilder()
            .configJson(parser.encode(declaredWithInput(32768)))
            .build());
    Model bounded =
        createAndCapture(
            groupCreate(
                "bounded-input", member("wide-input", "default"), member("small-ctx", "default")));
    ModelGroupDTO boundedDto =
        (ModelGroupDTO) service.toDto(bounded.toBuilder().version(0L).build());
    assertEquals(4096, boundedDto.getConfig().getLimit().getContext());
    assertNull(boundedDto.getConfig().getLimit().getInput());
  }

  private static ModelConfigDTO declaredWithInput(int input) {
    ModelConfigDTO config = validConfig();
    config.getLimit().setInput(input);
    return config;
  }

  private ModelLimitDTO derivedLimit(Model group) {
    ModelGroupDTO dto = (ModelGroupDTO) service.toDto(group.toBuilder().version(0L).build());
    return dto.getConfig().getLimit();
  }

  private Model createAndCapture(ModelCreateDTO create) {
    when(repository.create(any())).thenReturn(true);
    ArgumentCaptor<Model> captor = ArgumentCaptor.forClass(Model.class);
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
  private void stubMember(Model member) {
    when(repository.getByNameForUpdate(member.name())).thenReturn(member);
    when(repository.getByName(member.name())).thenReturn(member);
  }

  private Model existing(String name, long version) {
    return Model.builder()
        .name(name)
        .kind(ModelKind.MODEL)
        .providerName("provider")
        .modelId("wire-model")
        .configJson(parser.encode(validConfig()))
        .version(version)
        .build();
  }

  private static ModelConfigDTO validConfig() {
    return ModelTestData.buildConfig(
        32768, 4096, true, false, "standard", "v1", List.of("default"), "default");
  }

  private static SingleModelCreateDTO create(String name, String description) {
    SingleModelCreateDTO create = new SingleModelCreateDTO();
    create.setName(name);
    create.setProviderName("provider");
    create.setModelId("wire-model");
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private static SingleModelUpdateDTO update(String name, long expectedVersion) {
    SingleModelUpdateDTO update = new SingleModelUpdateDTO();
    update.setName(name);
    update.setProviderName("provider");
    update.setModelId("wire-model");
    update.setConfig(validConfig());
    update.setExpectedVersion(expectedVersion);
    return update;
  }

  private static ModelCreateDTO groupCreate(String name, ModelGroupMemberDTO... members) {
    ModelGroupCreateDTO create = new ModelGroupCreateDTO();
    create.setName(name);
    create.setGroup(group(members));
    return create;
  }

  private static ModelGroupConfigDTO group(ModelGroupMemberDTO... members) {
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(members));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(0);
    return group;
  }

  private static ModelGroupMemberDTO member(String modelName, String variant) {
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant(variant);
    return member;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
