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

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Provider 服务的输入规范化、凭据补丁、连接世代轮换与 CAS 失败映射（仓库为替身，不触库）。 */
public class ProviderServiceUnitTest {

  private final ProviderRepository repository = mock(ProviderRepository.class);
  private final ProviderModelDiscovery discovery = mock(ProviderModelDiscovery.class);
  private final ProviderService service = newService();

  private ProviderService newService() {
    ObjectMapper objectMapper = JsonMapper.builder().build();
    return new ProviderService(
        repository,
        new ProviderConfigurationCodec(objectMapper),
        new AgentEditableSupport(objectMapper),
        discovery);
  }

  // ---- 创建与规范化 ----

  @Test
  public void shouldRejectInvalidProviderConfiguration() {
    assertThrows(DomainException.class, () -> service.createProvider(null));
    assertThrows(DomainException.class, () -> service.createProvider(provider(" ", null)));

    // 协议类型是枚举，未知 wire 值在 HTTP 边界即被拒绝；服务仍防御性拒绝缺失类型。
    ProviderCreateDTO missingType = provider("provider", null);
    missingType.setProviderType(null);
    assertThrows(DomainException.class, () -> service.createProvider(missingType));

    ProviderCreateDTO invalidTimeout = provider("provider", null);
    invalidTimeout.setModelCallIdleTimeoutMillis(0L);
    assertThrows(DomainException.class, () -> service.createProvider(invalidTimeout));
    verify(repository, never()).create(any());
  }

  // 测试意图: 断言 name/baseUrl/credential 仍受 schema 列宽约束，而 description 已放开为 text
  @Test
  public void shouldEnforceProviderSchemaStringLimitsAfterNormalization() {
    assertThrows(
        DomainException.class,
        () -> service.createProvider(provider(" provider ", "c".repeat(512))));

    ProviderCreateDTO accepted = provider("n".repeat(64), "c".repeat(512));
    accepted.setDescription("d".repeat(4096));
    accepted.setBaseUrl("u".repeat(512));
    assertEquals("n".repeat(64), created(accepted).name());
    assertEquals("d".repeat(4096), created(accepted).description());

    assertThrows(
        DomainException.class, () -> service.createProvider(provider("n".repeat(65), null)));
    assertThrows(
        DomainException.class, () -> service.createProvider(provider("provider/name", null)));
    assertThrows(
        DomainException.class, () -> service.createProvider(provider("provider", "c".repeat(513))));
    ProviderCreateDTO oversizedBaseUrl = provider("provider", "c".repeat(512));
    oversizedBaseUrl.setBaseUrl("u".repeat(513));
    assertThrows(DomainException.class, () -> service.createProvider(oversizedBaseUrl));
  }

  @Test
  public void createAssignsConnectionGenerationAndKeepsMissingCredentialAbsent() {
    Provider created = created(provider("new-provider", "   "));

    assertNotNull(created.connectionGenerationId());
    assertNull(created.credential());
    assertNull(created.version());
  }

  @Test
  public void createMapsRepositoryFailures() {
    ProviderCreateDTO create = provider("provider", null);
    when(repository.create(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createProvider(create));

    // 创建直接依赖主键唯一约束：数据库重复键映射为 DomainException。
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(DomainException.class, () -> service.createProvider(create));
  }

  // ---- 更新、凭据补丁与连接世代 ----

  @Test
  public void shouldRetainCredentialOnKeepAndClearItOnClear() {
    Provider kept = updated(existing("initial-secret", "{}"), update(SecretUpdate.keep()));
    assertEquals("initial-secret", kept.credential());
    assertEquals(
        "{\"modelCallTimeoutMillis\":1800000,\"modelCallIdleTimeoutMillis\":120000}",
        kept.configJson());

    Provider cleared = updated(existing("initial-secret", "{}"), update(SecretUpdate.clear()));
    assertNull(cleared.credential());

    Provider set = updated(existing(null, "{}"), update(SecretUpdate.set("next-secret")));
    assertEquals("next-secret", set.credential());
  }

  @Test
  public void shouldPersistRequiredTimeoutsAsWholeReplacement() {
    ProviderUpdateDTO update = update(SecretUpdate.keep());
    update.setModelCallTimeoutMillis(Duration.ofMinutes(2).toMillis());
    update.setModelCallIdleTimeoutMillis(Duration.ofSeconds(3).toMillis());
    Provider first = updated(existing(null, "{}"), update);

    ProviderUpdateDTO next = update(SecretUpdate.keep());
    next.setModelCallTimeoutMillis(Duration.ofMinutes(3).toMillis());
    next.setModelCallIdleTimeoutMillis(Duration.ofSeconds(5).toMillis());
    Provider second = updated(first, next);

    assertEquals(
        "{\"modelCallTimeoutMillis\":180000,\"modelCallIdleTimeoutMillis\":5000}",
        second.configJson());
  }

  @Test
  public void shouldNotRetainMalformedExistingConfigurationCause() {
    // 测试意图：更新路径读取损坏的持久配置时，不把可能携带原始配置的解析器 cause 暴露给
    // Web 异常边界。
    Provider provider = existing("initial-secret", "{\"credential\":\"sensitive-value\"");
    when(repository.getByName("provider")).thenReturn(provider);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.updateProvider("provider", update(SecretUpdate.keep())));

    assertFalse(error.getMessage().contains("sensitive-value"));
    assertNull(error.getCause());
  }

  @Test
  public void shouldRotateConnectionGenerationIdOnCredentialOrBaseUrlOrTypeChange() {
    UUID original = UUID.randomUUID();
    Provider provider = existing("initial-secret", "{}", "http://localhost:8080", original);

    ProviderUpdateDTO credChange = update(SecretUpdate.set("new-secret"));
    credChange.setBaseUrl("http://localhost:8080");
    Provider afterCred = updated(provider, credChange);
    assertNotEquals(original, afterCred.connectionGenerationId());

    ProviderUpdateDTO urlChange = update(SecretUpdate.keep());
    urlChange.setBaseUrl("http://localhost:9090");
    Provider afterUrl = updated(afterCred, urlChange);
    assertNotEquals(afterCred.connectionGenerationId(), afterUrl.connectionGenerationId());
  }

  @Test
  public void shouldNotRotateConnectionGenerationIdOnDescriptionOrTimeoutOnlyChange() {
    UUID original = UUID.randomUUID();
    Provider provider = existing("initial-secret", "{}", "http://localhost:8080", original);

    ProviderUpdateDTO descChange = update(SecretUpdate.keep());
    descChange.setBaseUrl("http://localhost:8080");
    descChange.setDescription("new-description");
    Provider afterDesc = updated(provider, descChange);
    assertEquals(original, afterDesc.connectionGenerationId());

    ProviderUpdateDTO timeoutChange = update(SecretUpdate.keep());
    timeoutChange.setBaseUrl("http://localhost:8080");
    timeoutChange.setModelCallTimeoutMillis(60000L);
    timeoutChange.setModelCallIdleTimeoutMillis(10000L);
    assertEquals(original, updated(afterDesc, timeoutChange).connectionGenerationId());
  }

  @Test
  public void shouldAssignConnectionGenerationIdIfPreviouslyNull() {
    Provider provider = existing("initial-secret", "{}", null, null);

    assertNotNull(updated(provider, update(SecretUpdate.keep())).connectionGenerationId());
  }

  @Test
  public void updateRejectsMissingOrStaleExpectedVersionBeforeMutating() {
    assertThrows(
        DomainException.class, () -> service.updateProvider("provider", new ProviderUpdateDTO()));

    when(repository.getByName("provider")).thenReturn(existing(null, "{}", 1L));
    assertThrows(
        DomainException.class,
        () -> service.updateProvider("provider", versioned(update(SecretUpdate.keep()), 0L)));
    verify(repository, never()).updateByName(any(), anyLong());
  }

  @Test
  public void updateCasFailureDistinguishesMissingRowFromNewerVersion() {
    when(repository.getByName("provider")).thenReturn(existing(null, "{}"));
    when(repository.updateByName(any(), eq(0L))).thenReturn(false);
    ProviderUpdateDTO update = versioned(update(SecretUpdate.keep()), 0L);

    when(repository.getByName("provider")).thenReturn(existing(null, "{}"), (Provider) null);
    assertThrows(DomainException.class, () -> service.updateProvider("provider", update));

    when(repository.getByName("provider"))
        .thenReturn(existing(null, "{}"), existing(null, "{}", 5L));
    assertThrows(DomainException.class, () -> service.updateProvider("provider", update));
  }

  @Test
  public void updateOfMissingProviderIsNotFound() {
    assertThrows(
        DomainException.class,
        () -> service.updateProvider("missing", versioned(update(SecretUpdate.keep()), 0L)));
  }

  // ---- 导入 upsert ----

  /** 导入不存在的 Provider 走创建，复用普通 create 的唯一约束。 */
  @Test
  public void importCreatesWhenAbsent() {
    when(repository.create(any())).thenReturn(true);
    when(repository.getByName("provider")).thenReturn(null, existing(null, "{}"));

    ProviderDTO imported = service.importProvider(properties(ProviderType.OPENAI, null));

    assertEquals("provider", imported.getName());
    verify(repository).create(any());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  /** 导入已有 Provider 用读取到的当前版本做 CAS；null/空白凭据按文件事实清空，与普通更新的保留语义相反。 */
  @Test
  public void importUpdatesExistingWithReadVersionAndClearsMissingCredential() {
    Provider current = existing("initial-secret", "{}", null, UUID.randomUUID(), 3L);
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), eq(3L))).thenReturn(true);

    service.importProvider(properties(ProviderType.OPENAI, "   "));

    ArgumentCaptor<Provider> captor = ArgumentCaptor.forClass(Provider.class);
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
    Provider current = existing(null, "{}", "http://localhost:8080", original, 0L);
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), eq(0L))).thenReturn(true);
    ProviderCreateDTO props = properties(ProviderType.OPENAI, "restored-secret");
    props.setBaseUrl("http://localhost:8080");

    service.importProvider(props);

    ArgumentCaptor<Provider> captor = ArgumentCaptor.forClass(Provider.class);
    verify(repository).updateByName(captor.capture(), eq(0L));
    assertEquals("restored-secret", captor.getValue().credential());
    assertNotEquals(original, captor.getValue().connectionGenerationId());
  }

  /** 导入 CAS 失败：行已消失为未找到，版本已前进为版本冲突。 */
  @Test
  public void importCasFailureMapsToNotFoundOrConflict() {
    ProviderCreateDTO properties = properties(ProviderType.OPENAI, null);
    when(repository.updateByName(any(), eq(3L))).thenReturn(false);

    when(repository.getByName("provider")).thenReturn(existing(null, "{}", 3L), (Provider) null);
    assertThrows(DomainException.class, () -> service.importProvider(properties));

    when(repository.getByName("provider"))
        .thenReturn(existing(null, "{}", 3L), existing(null, "{}", 4L));
    assertThrows(DomainException.class, () -> service.importProvider(properties));
  }

  /** 导入创建遇到唯一约束竞争时映射为重复业务错误。 */
  @Test
  public void importCreateDuplicateKeyThrowsDuplicate() {
    when(repository.getByName("provider")).thenReturn(null);
    when(repository.create(any())).thenThrow(new DuplicateKeyException("dup"));

    assertThrows(
        DomainException.class, () -> service.importProvider(properties(ProviderType.OPENAI, null)));
  }

  @Test
  public void validateImportAppliesCreationRulesWithoutPersisting() {
    service.validateImport(properties(ProviderType.OPENAI, null));
    assertThrows(
        DomainException.class,
        () -> service.validateImport(propertiesWithName(" ", ProviderType.OPENAI, null)));
    assertThrows(
        DomainException.class,
        () -> service.validateImport(propertiesWithName("provider", null, null)));
    verify(repository, never()).create(any());
  }

  /** 同名不同类型：换协议即新建 Provider，导入必须拒绝，不能把既有模型接到另一协议上。 */
  @Test
  public void importRejectsTypeChangeOfExistingProvider() {
    when(repository.getByName("provider")).thenReturn(existing(ProviderType.ANTHROPIC, null, "{}"));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.importProvider(properties(ProviderType.OPENAI, null)));

    assertEquals(DomainErrorCode.CONFLICT, error.code());
    assertEquals("PROVIDER_TYPE_IMMUTABLE", error.reason());
    verify(repository, never()).updateByName(any(), anyLong());
  }

  // ---- 删除 ----

  @Test
  public void deleteChecksVersionBeforeReferencesAndMapsCasFailure() {
    assertThrows(DomainException.class, () -> service.deleteProvider("provider", 0L));

    Provider provider = existing(null, "{}", 1L);
    when(repository.getByNameForUpdate("provider")).thenReturn(provider);
    // 版本过期优先于引用检查。
    when(repository.hasModels("provider")).thenReturn(true);
    assertThrows(DomainException.class, () -> service.deleteProvider("provider", 0L));
    verify(repository, never()).hasModels("provider");

    assertThrows(DomainException.class, () -> service.deleteProvider("provider", 1L));
    verify(repository, never()).deleteByName(any(), anyLong());

    when(repository.hasModels("provider")).thenReturn(false);
    when(repository.deleteByName("provider", 1L)).thenReturn(false);
    when(repository.getByName("provider")).thenReturn(null);
    assertThrows(DomainException.class, () -> service.deleteProvider("provider", 1L));
    when(repository.getByName("provider")).thenReturn(existing(null, "{}", 5L));
    assertThrows(DomainException.class, () -> service.deleteProvider("provider", 1L));
  }

  // ---- 远端模型发现 ----

  /** 远端模型发现使用已保存的连接配置；地址错误与上游失败分别映射为校验与上游错误。 */
  @Test
  public void shouldListRemoteModelsWithSavedConnection() {
    Provider provider = existing("k", "{}", "https://h/v1", UUID.randomUUID());
    when(repository.getByName("provider")).thenReturn(provider);

    when(discovery.listModelIds(ProviderType.OPENAI, "https://h/v1", "k"))
        .thenReturn(List.of("m1"));
    assertEquals(List.of("m1"), service.listRemoteModelIds("provider"));

    when(discovery.listModelIds(ProviderType.OPENAI, "https://h/v1", "k"))
        .thenThrow(new IllegalArgumentException("bad url"));
    assertEquals(
        "bad url",
        assertThrows(DomainException.class, () -> service.listRemoteModelIds("provider"))
            .getMessage());

    doThrow(new ProviderModelDiscovery.Failure("HTTP 401"))
        .when(discovery)
        .listModelIds(ProviderType.OPENAI, "https://h/v1", "k");
    assertEquals(
        "HTTP 401",
        assertThrows(DomainException.class, () -> service.listRemoteModelIds("provider"))
            .getMessage());
  }

  // ---- 辅助 ----

  /** 创建并返回传给仓库的实体。 */
  private Provider created(ProviderCreateDTO dto) {
    when(repository.create(any())).thenReturn(true);
    when(repository.getByName(dto.getName())).thenReturn(existing(null, "{}"));
    service.createProvider(dto);
    ArgumentCaptor<Provider> captor = ArgumentCaptor.forClass(Provider.class);
    verify(repository, atLeastOnce()).create(captor.capture());
    return captor.getValue();
  }

  /** 以 expectedVersion 等于当前版本更新，并返回传给仓库的实体。 */
  private Provider updated(Provider current, ProviderUpdateDTO update) {
    when(repository.getByName("provider")).thenReturn(current);
    when(repository.updateByName(any(), anyLong())).thenReturn(true);
    versioned(update, current.version());
    service.updateProvider("provider", update);
    ArgumentCaptor<Provider> captor = ArgumentCaptor.forClass(Provider.class);
    verify(repository, atLeastOnce()).updateByName(captor.capture(), anyLong());
    return captor.getValue();
  }

  private static ProviderUpdateDTO versioned(ProviderUpdateDTO update, long version) {
    update.setExpectedVersion(version);
    return update;
  }

  private static ProviderUpdateDTO update(SecretUpdate credential) {
    ProviderUpdateDTO update = new ProviderUpdateDTO();
    update.setCredential(credential);
    update.setModelCallTimeoutMillis(1_800_000L);
    update.setModelCallIdleTimeoutMillis(120_000L);
    return update;
  }

  private static ProviderCreateDTO provider(String name, String credential) {
    return propertiesWithName(name, ProviderType.OPENAI, credential);
  }

  private static ProviderCreateDTO properties(ProviderType providerType, String credential) {
    ProviderCreateDTO properties = propertiesWithName("provider", providerType, credential);
    return properties;
  }

  private static ProviderCreateDTO propertiesWithName(
      String name, ProviderType providerType, String credential) {
    ProviderCreateDTO properties = new ProviderCreateDTO();
    properties.setName(name);
    properties.setProviderType(providerType);
    properties.setCredential(
        credential == null || credential.isBlank() ? null : SecretUpdate.set(credential));
    return properties;
  }

  private static Provider existing(String credential, String configJson) {
    return existing(ProviderType.OPENAI, credential, configJson);
  }

  private static Provider existing(
      ProviderType providerType, String credential, String configJson) {
    return new Provider(
        "provider",
        null,
        providerType,
        null,
        credential,
        configJson,
        UUID.randomUUID(),
        0L,
        null,
        null);
  }

  private static Provider existing(String credential, String configJson, long version) {
    return existing(credential, configJson, null, UUID.randomUUID(), version);
  }

  private static Provider existing(
      String credential, String configJson, String baseUrl, UUID generationId) {
    return existing(credential, configJson, baseUrl, generationId, 0L);
  }

  private static Provider existing(
      String credential, String configJson, String baseUrl, UUID generationId, long version) {
    return new Provider(
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
