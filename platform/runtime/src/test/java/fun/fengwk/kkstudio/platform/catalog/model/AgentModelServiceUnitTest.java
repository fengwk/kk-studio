package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/** 模型服务的输入规范化、可执行配置校验、CAS 失败分类与删除前置检查（仓库以 mock 隔离）。 */
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
            repository, providerRepository, new AgentEditableSupport(objectMapper), parser);
  }

  /** 创建要求可执行配置与非空 modelId；成功时保持 provider/name 身份。 */
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

    AgentModel created = createAndCapture(create("model", null));
    assertEquals("provider", created.providerName());
    assertEquals("model", created.name());
    assertEquals("wire-model", created.modelId());
  }

  /** Provider 不存在时 404，且不写入。 */
  @Test
  void createRejectsMissingProvider() {
    AgentModelCreateDTO create = create("model", null);
    create.setProviderName("ghost");
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));
    verify(repository, never()).create(any());
  }

  /** 编辑保留完整定价元数据。 */
  @Test
  void updatePreservesPricingMetadata() {
    AgentModel existing = existing("model", 0L);
    when(repository.getByProviderNameAndNameForUpdate("provider", "model")).thenReturn(existing);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    AgentModelConfigDTO config = validConfig();
    AgentModelPricingDTO pricing = config.getPricing();
    pricing.setCurrency("EUR");
    pricing.setPricingTier("batch");
    pricing.setServiceTier("priority");
    pricing.setServiceTierMultiplier(new BigDecimal("1.5"));
    pricing.setVersion("2026-08-01");
    pricing.setInputPerMillionTokens(new BigDecimal("7"));
    pricing.setOutputPerMillionTokens(new BigDecimal("9"));
    pricing.setCacheReadPerMillionTokens(new BigDecimal("1"));
    pricing.setCacheWritePerMillionTokens(new BigDecimal("2"));
    pricing.setCacheWriteLongPerMillionTokens(new BigDecimal("3"));
    pricing.setReasoningPerMillionTokens(new BigDecimal("4"));
    AgentModelUpdateDTO update = update("model", "0");
    update.setDescription("updated");
    update.setConfig(config);

    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(existing);
    service.updateModel("provider", "model", update);
    verify(repository).updateByName(captor.capture(), eq(0L));

    AgentModelConfigDTO persisted = parser.decode(captor.getValue().configJson());
    assertEquals("updated", captor.getValue().description());
    assertNotNull(persisted.getPricing());
    assertEquals("EUR", persisted.getPricing().getCurrency());
    assertEquals("batch", persisted.getPricing().getPricingTier());
    assertEquals("priority", persisted.getPricing().getServiceTier());
    assertEquals(new BigDecimal("1.5"), persisted.getPricing().getServiceTierMultiplier());
    assertEquals("2026-08-01", persisted.getPricing().getVersion());
    assertEquals(new BigDecimal("7"), persisted.getPricing().getInputPerMillionTokens());
    assertEquals(new BigDecimal("9"), persisted.getPricing().getOutputPerMillionTokens());
    assertEquals(new BigDecimal("1"), persisted.getPricing().getCacheReadPerMillionTokens());
    assertEquals(new BigDecimal("2"), persisted.getPricing().getCacheWritePerMillionTokens());
    assertEquals(new BigDecimal("3"), persisted.getPricing().getCacheWriteLongPerMillionTokens());
    assertEquals(new BigDecimal("4"), persisted.getPricing().getReasoningPerMillionTokens());
  }

  // 测试意图: name/modelId 仍受 schema 列宽约束，description 为 text 不拒绝长文本
  @Test
  void enforcesSchemaStringLimits() {
    AgentModel accepted = createAndCapture(create("n".repeat(128), "d".repeat(4096)));
    assertEquals("n".repeat(128), accepted.name());
    assertEquals("d".repeat(4096), accepted.description());

    assertThrows(AiValidationException.class, () -> service.createModel(create(" model ", null)));
    assertThrows(
        AiValidationException.class, () -> service.createModel(create("n".repeat(129), null)));
    AgentModelCreateDTO oversizedModelId = create("model", null);
    oversizedModelId.setModelId("m".repeat(257));
    assertThrows(AiValidationException.class, () -> service.createModel(oversizedModelId));
  }

  /** 改名走 rename 并校验目标名；非法目标名在写入前拒绝。 */
  @Test
  void renameValidatesTargetName() {
    AgentModel existing = existing("old-name", 0L);
    when(repository.getByProviderNameAndNameForUpdate("provider", "old-name")).thenReturn(existing);
    when(repository.getByProviderNameAndName("provider", "new-name"))
        .thenReturn(existing.toBuilder().name("new-name").version(1L).build());

    service.updateModel("provider", "old-name", update("new-name", "0"));
    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    verify(repository).rename(eq("old-name"), captor.capture(), eq(0L));
    assertEquals("new-name", captor.getValue().name());

    for (String invalid : List.of("   ", " padded-name ", "n".repeat(129))) {
      assertThrows(
          AiValidationException.class,
          () -> service.updateModel("provider", "old-name", update(invalid, "0")));
    }
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
    assertThrows(
        AiValidationException.class, () -> service.updateModel("provider", "model", missing));
    when(repository.getByProviderNameAndNameForUpdate("provider", "model")).thenReturn(model);
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateModel("provider", "model", update("model", "0")));
    AgentModel reread = existing("model", 3L);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateModel("provider", "model", update("model", "0")));
    when(repository.updateByName(any(), eq(0L))).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(
        AiDuplicateException.class,
        () -> service.updateModel("provider", "model", update("model", "0")));
    doThrow(new DuplicateKeyException("dup")).when(repository).rename(eq("model"), any(), eq(0L));
    assertThrows(
        AiDuplicateException.class,
        () -> service.updateModel("provider", "model", update("renamed-model", "0")));

    when(repository.deleteByName(eq("provider"), eq("model"), anyLong())).thenReturn(false);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.deleteModel("provider", "model", "0"));
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class, () -> service.deleteModel("provider", "model", "0"));
    when(repository.getByProviderNameAndNameForUpdate("provider", "ghost")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.deleteModel("provider", "ghost", "0"));
  }

  /** 版本过期优先于删除引用检查；仍被 Agent 引用的模型拒绝删除。 */
  @Test
  void staleVersionWinsOverDeletionChecks() {
    when(repository.getByProviderNameAndNameForUpdate("provider", "model"))
        .thenReturn(existing("model", 1L));
    when(repository.hasAgents("provider", "model")).thenReturn(true);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateModel("provider", "model", update("model", "0")));
    verify(repository, never()).updateByName(any(), anyLong());
    assertThrows(
        AiVersionConflictException.class, () -> service.deleteModel("provider", "model", "0"));
    verify(repository, never()).hasAgents("provider", "model");

    assertThrows(AiInUseException.class, () -> service.deleteModel("provider", "model", "1"));
    verify(repository, never()).deleteByName(any(), any(), anyLong());
  }

  /** 配置缺失的行无法投影为 DTO，校验失败而不是返回半个模型。 */
  @Test
  void dtoRequiresStoredConfig() {
    assertThrows(
        AiValidationException.class,
        () -> service.toDto(existing("model", 0L).toBuilder().configJson(" ").build()));
    assertEquals("model", service.toDto(existing("model", 0L)).getName());
  }

  private AgentModel createAndCapture(AgentModelCreateDTO create) {
    when(repository.create(any())).thenReturn(true);
    ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
    when(repository.getByProviderNameAndName(eq("provider"), any()))
        .thenAnswer(
            invocation -> {
              verify(repository, atLeastOnce()).create(captor.capture());
              return captor.getValue().toBuilder().version(0L).build();
            });
    service.createModel(create);
    return captor.getValue();
  }

  private AgentModel existing(String name, long version) {
    return AgentModel.builder()
        .providerName("provider")
        .name(name)
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
    create.setProviderName("provider");
    create.setModelId("wire-model");
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private static AgentModelUpdateDTO update(String name, String expectedVersion) {
    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setName(name);
    update.setModelId("wire-model");
    update.setConfig(validConfig());
    update.setExpectedVersion(expectedVersion);
    return update;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
