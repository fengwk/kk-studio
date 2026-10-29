package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiUpstreamException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Provider 服务的输入规范化、凭据补丁、连接世代轮换与 CAS 失败映射（仓库为替身，不触库）。 */
public class AgentProviderServiceUnitTest {

  private final AgentProviderRepository repository = mock(AgentProviderRepository.class);
  private final ProviderModelDiscovery discovery = mock(ProviderModelDiscovery.class);
  private final AgentProviderService service = newService();

  private AgentProviderService newService() {
    ObjectMapper objectMapper = JsonMapper.builder().build();
    return new AgentProviderService(
        repository,
        new AgentProviderConfigurationCodec(objectMapper),
        new AgentEditableSupport(objectMapper),
        discovery);
  }

  // ---- 创建与规范化 ----

  @Test
  public void shouldRejectInvalidProviderConfiguration() {
    assertThrows(AiValidationException.class, () -> service.createProvider(null));
    assertThrows(AiValidationException.class, () -> service.createProvider(provider(" ", null)));

    AgentProviderCreateDTO unsupported = provider("provider", null);
    unsupported.setProviderType("missing");
    assertThrows(AiValidationException.class, () -> service.createProvider(unsupported));
    // Provider wire 值不接受大小写变化或首尾空白，避免本地规范化掩盖非法 catalog 数据。
    unsupported.setProviderType("OPENAI");
    assertThrows(AiValidationException.class, () -> service.createProvider(unsupported));
    unsupported.setProviderType("openai ");
    assertThrows(AiValidationException.class, () -> service.createProvider(unsupported));

    AgentProviderCreateDTO invalidTimeout = provider("provider", null);
    invalidTimeout.setModelCallIdleTimeoutMillis(0L);
    assertThrows(AiValidationException.class, () -> service.createProvider(invalidTimeout));
    verify(repository, never()).create(any());
  }

  // 测试意图: 断言 name/baseUrl/credential 仍受 schema 列宽约束，而 description 已放开为 text
  @Test
  public void shouldEnforceProviderSchemaStringLimitsAfterNormalization() {
    assertThrows(
        AiValidationException.class,
        () -> service.createProvider(provider(" provider ", "c".repeat(512))));

    AgentProviderCreateDTO accepted = provider("n".repeat(64), "c".repeat(512));
    accepted.setDescription("d".repeat(4096));
    accepted.setBaseUrl("u".repeat(512));
    assertEquals("n".repeat(64), created(accepted).name());
    assertEquals("d".repeat(4096), created(accepted).description());

    assertThrows(
        AiValidationException.class, () -> service.createProvider(provider("n".repeat(65), null)));
    assertThrows(
        AiValidationException.class, () -> service.createProvider(provider("provider/name", null)));
    assertThrows(
        AiValidationException.class,
        () -> service.createProvider(provider("provider", "c".repeat(513))));
    AgentProviderCreateDTO oversizedBaseUrl = provider("provider", "c".repeat(512));
    oversizedBaseUrl.setBaseUrl("u".repeat(513));
    assertThrows(AiValidationException.class, () -> service.createProvider(oversizedBaseUrl));
  }

  @Test
  public void createAssignsConnectionGenerationAndKeepsMissingCredentialAbsent() {
    AgentProvider created = created(provider("new-provider", "   "));

    assertNotNull(created.connectionGenerationId());
    assertNull(created.credential());
    assertNull(created.version());
  }

  @Test
  public void createMapsRepositoryFailures() {
    AgentProviderCreateDTO create = provider("provider", null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createProvider(create));

    // 创建直接依赖主键唯一约束：数据库重复键映射为 AiDuplicateException。
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createProvider(create));
  }

  // ---- 更新、凭据补丁与连接世代 ----

  @Test
  public void shouldRetainCredentialWhenUpdateDoesNotProvideOne() {
    AgentProvider updated = updated(existing("initial-secret", "{}"), update(" "));

    assertEquals("initial-secret", updated.credential());
    assertEquals(
        "{\"modelCallTimeoutMillis\":1800000,\"modelCallIdleTimeoutMillis\":120000}",
        updated.configJson());
  }

  @Test
  public void shouldPersistConfiguredTimeoutsAndRetainExistingValuesOnPartialUpdate() {
    AgentProviderUpdateDTO update = update(null);
    update.setModelCallTimeoutMillis(Duration.ofMinutes(2).toMillis());
    update.setModelCallIdleTimeoutMillis(Duration.ofSeconds(3).toMillis());
    AgentProvider first = updated(existing(null, "{}"), update);

    AgentProviderUpdateDTO partial = update(null);
    partial.setModelCallIdleTimeoutMillis(Duration.ofSeconds(5).toMillis());
    AgentProvider second = updated(first, partial);

    assertEquals(
        "{\"modelCallTimeoutMillis\":120000,\"modelCallIdleTimeoutMillis\":5000}",
        second.configJson());
  }

  @Test
  public void shouldNotRetainMalformedExistingConfigurationCause() {
    // 测试意图：更新路径读取损坏的持久配置时，不把可能携带原始配置的解析器 cause 暴露给
    // Web 异常边界。
    AgentProvider provider = existing("initial-secret", "{\"credential\":\"sensitive-value\"");
    when(repository.getByName("provider")).thenReturn(provider);

    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> service.updateProvider("provider", update(null)));

    assertFalse(error.getMessage().contains("sensitive-value"));
    assertNull(error.getCause());
  }

  @Test
  public void shouldRotateConnectionGenerationIdOnCredentialOrBaseUrlOrTypeChange() {
    UUID original = UUID.randomUUID();
    AgentProvider provider = existing("initial-secret", "{}", "http://localhost:8080", original);

    AgentProviderUpdateDTO credChange = update("new-secret");
    credChange.setBaseUrl("http://localhost:8080");
    AgentProvider afterCred = updated(provider, credChange);
    assertNotEquals(original, afterCred.connectionGenerationId());

    AgentProviderUpdateDTO urlChange = update(null);
    urlChange.setBaseUrl("http://localhost:9090");
    AgentProvider afterUrl = updated(afterCred, urlChange);
    assertNotEquals(afterCred.connectionGenerationId(), afterUrl.connectionGenerationId());

    AgentProviderUpdateDTO typeChange = update(null);
    typeChange.setProviderType("openai_responses");
    typeChange.setBaseUrl("http://localhost:9090");
    AgentProvider afterType = updated(afterUrl, typeChange);
    assertNotEquals(afterUrl.connectionGenerationId(), afterType.connectionGenerationId());
  }

  @Test
  public void shouldNotRotateConnectionGenerationIdOnDescriptionOrTimeoutOnlyChange() {
    UUID original = UUID.randomUUID();
    AgentProvider provider = existing("initial-secret", "{}", "http://localhost:8080", original);

    AgentProviderUpdateDTO descChange = update(null);
    descChange.setBaseUrl("http://localhost:8080");
    descChange.setDescription("new-description");
    AgentProvider afterDesc = updated(provider, descChange);
    assertEquals(original, afterDesc.connectionGenerationId());

    AgentProviderUpdateDTO timeoutChange = update(null);
    timeoutChange.setBaseUrl("http://localhost:8080");
    timeoutChange.setModelCallTimeoutMillis(60000L);
    timeoutChange.setModelCallIdleTimeoutMillis(10000L);
    assertEquals(original, updated(afterDesc, timeoutChange).connectionGenerationId());
  }

  @Test
  public void shouldAssignConnectionGenerationIdIfPreviouslyNull() {
    AgentProvider provider = existing("initial-secret", "{}", null, null);

    assertNotNull(updated(provider, update(null)).connectionGenerationId());
  }

  @Test
  public void updateRejectsMissingOrStaleExpectedVersionBeforeMutating() {
    assertThrows(
        AiValidationException.class,
        () -> service.updateProvider("provider", new AgentProviderUpdateDTO()));

    when(repository.getByName("provider")).thenReturn(existing(null, "{}", 1L));
    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateProvider("provider", versioned(update(null), "0")));
    verify(repository, never()).updateByName(any(), anyLong());
  }

  @Test
  public void updateCasFailureDistinguishesMissingRowFromNewerVersion() {
    when(repository.getByName("provider")).thenReturn(existing(null, "{}"));
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    AgentProviderUpdateDTO update = versioned(update(null), "0");

    when(repository.getByName("provider")).thenReturn(existing(null, "{}"), (AgentProvider) null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.updateProvider("provider", update));

    when(repository.getByName("provider"))
        .thenReturn(existing(null, "{}"), existing(null, "{}", 5L));
    assertThrows(
        AiVersionConflictException.class, () -> service.updateProvider("provider", update));
  }

  @Test
  public void updateOfMissingProviderIsNotFound() {
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateProvider("missing", versioned(update(null), "0")));
  }

  // ---- 导入 upsert ----

  /** 导入不存在的 Provider 走创建，复用普通 create 的唯一约束。 */
  @Test
  public void importCreatesWhenAbsent() {
    when(repository.create(any())).thenReturn(true);
    when(repository.getByName("provider")).thenReturn(null, existing(null, "{}"));

    AgentProviderDTO imported = service.importProvider("provider", properties("openai", null));

    assertEquals("provider", imported.getName());
    verify(repository).create(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 导入已有 Provider 用读取到的当前版本做 CAS；null/空白凭据按文件事实清空，与普通更新的保留语义相反。 */
  @Test
  public void importUpdatesExistingWithReadVersionAndClearsMissingCredential() {
    AgentProvider current = existing("initial-secret", "{}", null, UUID.randomUUID(), 3L);
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), eq(3L))).thenReturn(true);

    service.importProvider("provider", properties("openai", "   "));

    ArgumentCaptor<AgentProvider> captor = ArgumentCaptor.forClass(AgentProvider.class);
    verify(repository).updateByName(captor.capture(), eq(3L));
    assertNull(captor.getValue().credential());
    // 凭据由有值变为无值，连接世代轮换。
    assertNotEquals(current.connectionGenerationId(), captor.getValue().connectionGenerationId());
    verify(repository, never()).create(any());
  }

  /** 导入恢复原始凭据时写入该值，并在凭据变更时轮换 connection generation。 */
  @Test
  public void importRestoresCredentialAndRotatesGeneration() {
    UUID original = UUID.randomUUID();
    AgentProvider current = existing(null, "{}", "http://localhost:8080", original, 0L);
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    AgentProviderEditablePropertiesDTO props = properties("openai", "restored-secret");
    props.setBaseUrl("http://localhost:8080");

    service.importProvider("provider", props);

    ArgumentCaptor<AgentProvider> captor = ArgumentCaptor.forClass(AgentProvider.class);
    verify(repository).updateByName(captor.capture(), eq(0L));
    assertEquals("restored-secret", captor.getValue().credential());
    assertNotEquals(original, captor.getValue().connectionGenerationId());
  }

  /** 导入 CAS 失败：行已消失为未找到，版本已前进为版本冲突。 */
  @Test
  public void importCasFailureMapsToNotFoundOrConflict() {
    AgentProviderEditablePropertiesDTO properties = properties("openai", null);
    when(repository.updateByName(any(), eq(3L))).thenReturn(false);

    when(repository.getByName("provider"))
        .thenReturn(existing(null, "{}", 3L), (AgentProvider) null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.importProvider("provider", properties));

    when(repository.getByName("provider"))
        .thenReturn(existing(null, "{}", 3L), existing(null, "{}", 4L));
    assertThrows(
        AiVersionConflictException.class, () -> service.importProvider("provider", properties));
  }

  /** 导入创建遇到唯一约束竞争时映射为重复业务错误。 */
  @Test
  public void importCreateDuplicateKeyThrowsDuplicate() {
    when(repository.getByName("provider")).thenReturn(null);
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));

    assertThrows(
        AiDuplicateException.class,
        () -> service.importProvider("provider", properties("openai", null)));
  }

  @Test
  public void validateImportAppliesCreationRulesWithoutPersisting() {
    service.validateImport("provider", properties("openai", null));
    assertThrows(
        AiValidationException.class,
        () -> service.validateImport("provider", properties("missing", null)));
    assertThrows(
        AiValidationException.class, () -> service.validateImport(" ", properties("openai", null)));
    verify(repository, never()).create(any());
  }

  // ---- 删除 ----

  @Test
  public void deleteChecksVersionBeforeReferencesAndMapsCasFailure() {
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteProvider("provider", "0"));

    AgentProvider provider = existing(null, "{}", 1L);
    when(repository.getByNameForUpdate("provider")).thenReturn(provider);
    // 版本过期优先于引用检查。
    when(repository.hasModels("provider")).thenReturn(true);
    assertThrows(AiVersionConflictException.class, () -> service.deleteProvider("provider", "0"));
    verify(repository, never()).hasModels("provider");

    assertThrows(AiInUseException.class, () -> service.deleteProvider("provider", "1"));
    verify(repository, never()).deleteByName(any(), anyLong());

    when(repository.hasModels("provider")).thenReturn(false);
    when(repository.deleteByName("provider", 1L)).thenReturn(false);
    when(repository.getByName("provider")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteProvider("provider", "1"));
    when(repository.getByName("provider")).thenReturn(existing(null, "{}", 5L));
    assertThrows(AiVersionConflictException.class, () -> service.deleteProvider("provider", "1"));
  }

  // ---- 远端模型发现 ----

  /** 远端模型发现使用已保存的连接配置；地址错误与上游失败分别映射为校验与上游错误。 */
  @Test
  public void shouldListRemoteModelsWithSavedConnection() {
    AgentProvider provider = existing("k", "{}", "https://h/v1", UUID.randomUUID());
    when(repository.getByName("provider")).thenReturn(provider);

    when(discovery.listModelIds(ProviderType.OPENAI, "https://h/v1", "k"))
        .thenReturn(List.of("m1"));
    assertEquals(List.of("m1"), service.listRemoteModelIds("provider"));

    when(discovery.listModelIds(ProviderType.OPENAI, "https://h/v1", "k"))
        .thenThrow(new IllegalArgumentException("bad url"));
    assertEquals(
        "bad url",
        assertThrows(AiValidationException.class, () -> service.listRemoteModelIds("provider"))
            .getMessage());

    doThrow(new ProviderModelDiscovery.Failure("HTTP 401"))
        .when(discovery)
        .listModelIds(ProviderType.OPENAI, "https://h/v1", "k");
    assertEquals(
        "HTTP 401",
        assertThrows(AiUpstreamException.class, () -> service.listRemoteModelIds("provider"))
            .getMessage());
  }

  // ---- 辅助 ----

  /** 创建并返回传给仓库的实体。 */
  private AgentProvider created(AgentProviderCreateDTO dto) {
    when(repository.create(any())).thenReturn(true);
    when(repository.getByName(dto.getName())).thenReturn(existing(null, "{}"));
    service.createProvider(dto);
    ArgumentCaptor<AgentProvider> captor = ArgumentCaptor.forClass(AgentProvider.class);
    verify(repository, atLeastOnce()).create(captor.capture());
    return captor.getValue();
  }

  /** 以 expectedVersion 等于当前版本更新，并返回传给仓库的实体。 */
  private AgentProvider updated(AgentProvider current, AgentProviderUpdateDTO update) {
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), anyLong())).thenReturn(true);
    versioned(update, String.valueOf(current.version()));
    service.updateProvider("provider", update);
    ArgumentCaptor<AgentProvider> captor = ArgumentCaptor.forClass(AgentProvider.class);
    verify(repository, atLeastOnce()).updateByName(captor.capture(), anyLong());
    return captor.getValue();
  }

  private static AgentProviderUpdateDTO versioned(AgentProviderUpdateDTO update, String version) {
    update.setExpectedVersion(version);
    return update;
  }

  private static AgentProviderUpdateDTO update(String credential) {
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(credential);
    return update;
  }

  private static AgentProviderCreateDTO provider(String name, String credential) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    dto.setCredential(credential);
    return dto;
  }

  private static AgentProviderEditablePropertiesDTO properties(
      String providerType, String credential) {
    AgentProviderEditablePropertiesDTO properties = new AgentProviderEditablePropertiesDTO();
    properties.setProviderType(providerType);
    properties.setCredential(credential);
    return properties;
  }

  private static AgentProvider existing(String credential, String configJson) {
    return existing(credential, configJson, null, UUID.randomUUID(), 0L);
  }

  private static AgentProvider existing(String credential, String configJson, long version) {
    return existing(credential, configJson, null, UUID.randomUUID(), version);
  }

  private static AgentProvider existing(
      String credential, String configJson, String baseUrl, UUID generationId) {
    return existing(credential, configJson, baseUrl, generationId, 0L);
  }

  private static AgentProvider existing(
      String credential, String configJson, String baseUrl, UUID generationId, long version) {
    return new AgentProvider(
        "provider",
        null,
        ProviderType.OPENAI,
        baseUrl,
        credential,
        configJson,
        generationId,
        version,
        null,
        null);
  }
}
