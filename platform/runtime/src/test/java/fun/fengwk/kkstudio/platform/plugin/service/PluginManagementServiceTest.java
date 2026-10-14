package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryTransactionManager;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Plugin 管理面用例契约测试（内存权威 + 真实启动装配）。
 *
 * <p>覆盖 listPlugins / getPlugin / prepareAuth / completeAuth / disconnect / updateConfiguration：未安装
 * Plugin 抛 404、投影不泄漏秘密、一次性 attempt 的单次消费、迟到的坐标漂移被拒、完成材料的 region 必须与 prepare 一致、无秘密操作或撤销秘密不需要主密钥，
 * 以及只有真正重写秘密的分支才要求主密钥。
 */
class PluginManagementServiceTest {

  @TempDir Path tempDir;

  private static final String PLUGIN_ID = "managed-plugin";
  private static final String NO_AUTH_PLUGIN_ID = "no-auth-plugin";
  private static final String STATIC_KEY_PLUGIN_ID = "static-key-plugin";
  private static final String REGION_CN = "CN";
  private static final String REGION_US = "US";
  private static final String SECRET_TOKEN_MARKER = "very-secret-callback-token-marker-654321";

  private final Instant now = Instant.parse("2026-09-21T14:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private InMemoryPluginCredentialRepository credentials;
  private InMemoryPluginAuthAttemptRepository attempts;
  private InMemoryPluginConfigurationRepository catalog;
  private InMemoryTransactionManager transactions;
  private DatabasePluginCredentialStore credentialStore;
  private DatabasePluginCredentialStore credentialStoreWithoutKey;
  private PluginConfigurationCommitService commitService;
  private PluginConfigurationCommitService commitServiceWithoutKey;
  private PluginCatalogSnapshot snapshot;
  private StudioPluginRegistry registry;
  private PluginManagementService service;
  private TestAuthPlugin authPlugin;
  private TestStaticKeyPlugin staticKeyPlugin;

  @BeforeEach
  void setUp() throws IOException {
    credentials = new InMemoryPluginCredentialRepository();
    catalog = new InMemoryPluginConfigurationRepository();
    attempts = new InMemoryPluginAuthAttemptRepository(catalog);
    transactions = new InMemoryTransactionManager();
    PluginCredentialCodec codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("mgmt-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    PluginCredentialKeyLoader missingKeyLoader =
        new PluginCredentialKeyLoader(tempDir.resolve("missing.key").toAbsolutePath().toString());

    authPlugin = new TestAuthPlugin();
    staticKeyPlugin = new TestStaticKeyPlugin();
    registry =
        new StudioPluginRegistry(List.of(authPlugin, new TestNoAuthPlugin(), staticKeyPlugin));

    // 用真实启动装配建立 epoch 1 的 manifest 与配置历史：管理面与提交服务都只看权威行。
    snapshot =
        new PluginCatalogBootstrap(registry, catalog, transactions)
            .bootstrap(
                (pluginId, reference) ->
                    new PluginContext(new PluginCredentialAccessStub(reference), Optional.empty()),
                List.of(),
                List.of());

    credentialStore =
        new DatabasePluginCredentialStore(credentials, catalog, codec, keyLoader, clock);
    credentialStoreWithoutKey =
        new DatabasePluginCredentialStore(credentials, catalog, codec, missingKeyLoader, clock);
    commitService = commitService(keyLoader, codec);
    commitServiceWithoutKey = commitService(missingKeyLoader, codec);
    service = service(credentialStore, commitService);
  }

  private PluginConfigurationCommitService commitService(
      PluginCredentialKeyLoader keyLoader, PluginCredentialCodec codec) {
    return new PluginConfigurationCommitService(
        registry,
        catalog,
        credentials,
        codec,
        keyLoader,
        new PluginContextFactory(credentialStore, () -> null),
        List::of,
        List::of,
        transactions,
        clock,
        () -> {});
  }

  private PluginManagementService service(
      DatabasePluginCredentialStore store, PluginConfigurationCommitService commits) {
    return new PluginManagementService(
        registry, () -> snapshot, catalog, store, commits, attempts, Duration.ofMinutes(10), clock);
  }

  /** listPlugins() 顺序与 registry 一致；DTO 绝不泄漏敏感密钥或回调参数。 */
  @Test
  void listPluginsOrderMatchesRegistryAndExcludesSecretMarkers() {
    completeAuthOfManagedPlugin("{\"token\":\"" + SECRET_TOKEN_MARKER + "\"}");

    List<PluginDTO> dtos = service.listPlugins();
    assertEquals(3, dtos.size());
    assertEquals(
        registry.plugins().stream().map(plugin -> plugin.descriptor().pluginId()).toList(),
        dtos.stream().map(PluginDTO::getPluginId).toList(),
        "listPlugins() order must match registry.plugins()");

    for (PluginDTO dto : dtos) {
      assertFalse(
          dto.toString().contains(SECRET_TOKEN_MARKER), "DTO toString() must not leak secret");
      if (dto.getPluginId().equals(PLUGIN_ID)) {
        assertEquals(PluginCredentialStatus.CONNECTED.name(), dto.getStatus());
        assertEquals(REGION_CN, dto.getRegion());
        assertNotNull(dto.getExpiresAt());
        assertEquals(
            "1",
            dto.getAppliedEpoch(),
            "this node still runs the bootstrapped snapshot: applied must not be faked forward");
        assertEquals("2", dto.getDesiredEpoch(), "the save advanced the desired epoch");
        assertEquals(
            PluginConfigurationCommitService.PENDING_APPLICATION_ERROR,
            dto.getApplicationError(),
            "a save that this node has not applied must be reported as pending");
      }
    }
  }

  /** authKind 映射：DeepLink 给 region 候选，StaticKey 给唯一秘密字段名，None 为 null 且不假装已连接。 */
  @Test
  void authKindMappingMatchesCapability() {
    PluginAuthKindDTO deepLinkKind = service.getPlugin(PLUGIN_ID).getAuthKind();
    assertInstanceOf(PluginAuthKindDTO.DeepLink.class, deepLinkKind);
    assertEquals(
        List.of(REGION_CN, REGION_US),
        ((PluginAuthKindDTO.DeepLink) deepLinkKind).regionCandidates());

    PluginAuthKindDTO staticKeyKind = service.getPlugin(STATIC_KEY_PLUGIN_ID).getAuthKind();
    assertInstanceOf(PluginAuthKindDTO.StaticKey.class, staticKeyKind);
    assertEquals("apiKey", ((PluginAuthKindDTO.StaticKey) staticKeyKind).secretField());

    PluginDTO noAuthDTO = service.getPlugin(NO_AUTH_PLUGIN_ID);
    assertNull(noAuthDTO.getAuthKind(), "Plugin without auth capability must have null authKind");
    assertNull(noAuthDTO.getStatus(), "no auth capability means no credential status at all");
    assertNull(noAuthDTO.getRegion());
    assertNull(noAuthDTO.getExpiresAt());
    assertNull(noAuthDTO.getNextRefreshAt());
    assertNull(noAuthDTO.getLastRefreshedAt());
    assertNull(noAuthDTO.getLastRefreshError());
  }

  /** getPlugin() 查询未安装 Plugin 时抛出 AiResourceNotFoundException。 */
  @Test
  void getPluginThrowsNotFoundForUninstalledPlugin() {
    assertThrows(AiResourceNotFoundException.class, () -> service.getPlugin("uninstalled-plugin"));
  }

  /** prepareAuth() 校验：未知 region、无 authHandler、loginUrl 为空/空白均抛出 AiValidationException。 */
  @Test
  void prepareAuthValidation() {
    assertThrows(
        AiResourceNotFoundException.class, () -> service.prepareAuth("unknown", REGION_CN));
    assertThrows(
        AiValidationException.class, () -> service.prepareAuth(NO_AUTH_PLUGIN_ID, REGION_CN));
    assertThrows(
        AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, "UNKNOWN_REGION"));

    authPlugin.setLoginUrlSupplier(region -> "   ");
    assertThrows(AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, REGION_CN));

    authPlugin.setLoginUrlSupplier(
        region -> {
          throw new RuntimeException("upstream network failure");
        });
    AiValidationException loginFailure =
        assertThrows(AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, REGION_CN));
    assertNull(loginFailure.getCause(), "raw login exception must not be attached as a cause");
    assertEquals(0, attempts.size(), "a rejected prepare must not leave a pending attempt");
  }

  /** prepareAuth() 成功时写入唯一 pending attempt：返回句柄与登录地址，落库的只有 nonce 摘要与期望坐标。 */
  @Test
  void prepareAuthStoresSinglePendingAttemptWithoutLeakingTheNonce() {
    authPlugin.setLoginUrlSupplier(region -> "https://auth.example.com/login?region=" + region);

    PluginAuthPrepareDTO first = service.prepareAuth(PLUGIN_ID, REGION_CN);

    assertEquals("https://auth.example.com/login?region=CN", first.getLoginUrl());
    assertNotNull(first.getAuthAttemptId());
    assertEquals(1, attempts.size());
    PluginAuthAttemptRow stored = attempts.find(PLUGIN_ID).orElseThrow();
    assertEquals(PluginAuthNonce.hash(first.getAuthAttemptId()), stored.nonceHash());
    assertNotEquals(
        first.getAuthAttemptId(),
        stored.nonceHash(),
        "the raw handle must never be stored as the nonce hash");
    assertFalse(stored.nonceHash().contains(first.getAuthAttemptId()));
    assertEquals(REGION_CN, stored.region());
    assertEquals(now.plus(Duration.ofMinutes(10)), stored.expiresAt());
    assertEquals(1L, stored.configRevision());
    assertEquals(
        catalog.readCatalog().manifest().plugins().get(PLUGIN_ID).authGeneration(),
        stored.authGeneration());

    // 新的 prepare 立刻取代旧的 pending attempt：旧句柄失效，存储里仍只有一行。
    PluginAuthPrepareDTO second = service.prepareAuth(PLUGIN_ID, REGION_US);
    assertNotEquals(first.getAuthAttemptId(), second.getAuthAttemptId());
    assertEquals(1, attempts.size());
    assertEquals(
        PluginAuthNonce.hash(second.getAuthAttemptId()),
        attempts.find(PLUGIN_ID).orElseThrow().nonceHash());
  }

  /**
   * completeAuth() 校验与去敏：空白 attemptId/callbackUrl 抛异常；handler 抛异常时异常消息与 toString 绝不泄漏回调原文； handler
   * 返回的 region 必须与 prepare 选定的 region 逐字相等，否则不写 store。
   */
  @Test
  void completeAuthValidationAndSanitization() {
    assertThrows(
        AiResourceNotFoundException.class, () -> service.completeAuth("unknown", "a", "b"));
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(NO_AUTH_PLUGIN_ID, "a", "https://callback.com/ok"));
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, null, "https://callback.com/ok"));
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, "  ", "https://callback.com/ok"));
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, "handle", null));
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, "handle", "   "));

    // 未经 prepare 的句柄：不得消费、不得落库，且错误里不含句柄原文。
    AiValidationException unknown =
        assertThrows(
            AiValidationException.class,
            () -> service.completeAuth(PLUGIN_ID, "unknown-handle", "https://callback.com/ok"));
    assertFalse(unknown.getMessage().contains("unknown-handle"));
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    String handle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();

    // handler 抛异常：断言异常本身及整条 cause/suppressed 链都不含回调原文中的敏感参数。
    String sensitiveCallback =
        "https://example.com/callback?code=abc&secret_token=" + SECRET_TOKEN_MARKER;
    authPlugin.setCompleteHandler(
        url -> {
          throw new RuntimeException("failed callback parsing: " + url);
        });
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> service.completeAuth(PLUGIN_ID, handle, sensitiveCallback));
    assertFalse(ex.getMessage().contains(SECRET_TOKEN_MARKER));
    assertFalse(ex.toString().contains(SECRET_TOKEN_MARKER));
    assertNull(ex.getCause(), "raw callback exception must not be attached as a cause");
    assertFalse(
        leaksMarker(ex, SECRET_TOKEN_MARKER),
        "no throwable in the cause/suppressed chain may leak the callback");
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED,
        credentialStore.projection(PLUGIN_ID).status(),
        "a failed completion must not write the store");

    // 句柄已在上面被消费：重放同一个句柄必须失败（单次消费）。
    String replayHandle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_US, now.plusSeconds(3600), now.plusSeconds(1800), "{\"token\":\"x\"}"));
    // prepare 选定 CN，材料却给 US：即使仍在 region 候选集合内也必须拒绝，且不写 store。
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, replayHandle, "https://callback.com/ok"));
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED,
        credentialStore.projection(PLUGIN_ID).status(),
        "store must not be written when the completed region differs from the prepared region");

    // 合法完成：写 store 并返回更新后的投影。
    String validHandle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_CN,
                now.plusSeconds(3600),
                now.plusSeconds(1800),
                "{\"token\":\"valid-complete-token\"}"));
    PluginDTO result = service.completeAuth(PLUGIN_ID, validHandle, "https://callback.com/valid");
    assertEquals(PluginCredentialStatus.CONNECTED.name(), result.getStatus());
    assertEquals(REGION_CN, result.getRegion());
    assertEquals(PluginCredentialStatus.CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    // 同一个句柄再消费一次必须失败：attempt 行已被原子删除。
    AiValidationException replayed =
        assertThrows(
            AiValidationException.class,
            () -> service.completeAuth(PLUGIN_ID, validHandle, "https://callback.com/valid"));
    assertFalse(replayed.getMessage().contains(validHandle));
  }

  /** attempt 过期后 complete 必须失败；过期 attempt 只被消费语句拒绝，不会被当成有效凭据。 */
  @Test
  void completeAuthRejectsExpiredAttempt() {
    String handle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    PluginManagementService expiredClockService =
        new PluginManagementService(
            registry,
            () -> snapshot,
            catalog,
            credentialStore,
            commitService,
            attempts,
            Duration.ofMinutes(10),
            Clock.fixed(now.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));

    assertThrows(
        AiValidationException.class,
        () -> expiredClockService.completeAuth(PLUGIN_ID, handle, "https://callback.com/ok"));
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());
  }

  /** prepare 之后配置已前进（配置变更）：迟到回调必须以冲突失败，绝不覆盖新设置。 */
  @Test
  void completeAuthRejectsLateCallbackAfterConfigurationMovedOn() {
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_CN, now.plusSeconds(3600), now.plusSeconds(1800), "{\"token\":\"late\"}"));
    String handle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    // prepare 之后该 Plugin 的配置被另一个 writer 提交了一次普通配置变更：它的 configRevision 前进。
    service.updateConfiguration(
        PLUGIN_ID,
        catalog.readCatalog().epoch(),
        catalog.readCatalog().manifest().plugins().get(PLUGIN_ID).configRevision(),
        "{}",
        Map.of());

    assertThrows(
        AiVersionConflictException.class,
        () -> service.completeAuth(PLUGIN_ID, handle, "https://callback.com/ok"));
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());
  }

  /** disconnect() 契约：已连接行删除且幂等（连续调用不报错）；未安装 pluginId 抛出 AiResourceNotFoundException。 */
  @Test
  void disconnectIdempotencyAndNotFound() {
    completeAuthOfManagedPlugin("{\"token\":\"abc\"}");
    assertEquals(PluginCredentialStatus.CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    service.disconnect(PLUGIN_ID);
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    service.disconnect(PLUGIN_ID);
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    assertThrows(AiResourceNotFoundException.class, () -> service.disconnect("uninstalled-plugin"));
  }

  /** 断连不需要主密钥：撤销（删除凭据行）在缺主密钥的部署里也必须可执行。 */
  @Test
  void disconnectDoesNotRequireTheMasterKey() {
    completeAuthOfManagedPlugin("{\"token\":\"abc\"}");
    PluginManagementService keylessService = service(credentialStore, commitServiceWithoutKey);

    keylessService.disconnect(PLUGIN_ID);

    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());
  }

  /** 纯 KEEP 的普通配置提交不需要主密钥，只有确实要重写秘密的 SET 才要求主密钥。 */
  @Test
  void plainConfigurationUpdateDoesNotRequireTheMasterKeyButSecretSetDoes() {
    PluginManagementService keylessService = service(credentialStore, commitServiceWithoutKey);
    long epoch = catalog.readCatalog().epoch();
    long revision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();

    // KEEP-only：没有秘密操作，也没有上下文变化，不需要主密钥。
    PluginDTO updated =
        keylessService.updateConfiguration(
            STATIC_KEY_PLUGIN_ID,
            epoch,
            revision,
            "{\"endpoint\":\"https://api.example.com\"}",
            Map.of());
    assertNotNull(updated);

    long nextEpoch = catalog.readCatalog().epoch();
    long nextRevision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();
    PluginSecretPatch set = PluginSecretPatch.set("k-" + SECRET_TOKEN_MARKER);

    // SET 必须重写密文，因此缺主密钥时 fail closed，且不写任何凭据。
    assertThrows(
        PluginKeyUnavailableException.class,
        () ->
            keylessService.updateConfiguration(
                STATIC_KEY_PLUGIN_ID,
                nextEpoch,
                nextRevision,
                "{\"endpoint\":\"https://api.example.com\"}",
                Map.of("apiKey", set)));
    assertNull(credentials.getDirect(STATIC_KEY_PLUGIN_ID), "no credential row may be written");

    // 有主密钥时 SET 落库：凭据行存在，且该 revision 历史只记录「已录入的字段名」，绝不记录值。
    PluginDTO connected =
        service.updateConfiguration(
            STATIC_KEY_PLUGIN_ID,
            nextEpoch,
            nextRevision,
            "{\"endpoint\":\"https://api.example.com\"}",
            Map.of("apiKey", set));
    assertEquals(PluginCredentialStatus.CONNECTED.name(), connected.getStatus());
    assertNotNull(credentials.getDirect(STATIC_KEY_PLUGIN_ID), "SET must write the credential row");
    long storedRevision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();
    assertEquals(
        List.of("apiKey"),
        catalog
            .readConfiguration(STATIC_KEY_PLUGIN_ID, storedRevision)
            .orElseThrow()
            .configuredSecretFields(),
        "the revision history must only record the configured field names");
    assertFalse(connected.toString().contains(SECRET_TOKEN_MARKER));
    assertFalse(
        connected.getConfiguration().toString().contains(SECRET_TOKEN_MARKER),
        "the configuration projection must never carry a secret value");
    assertTrue(
        connected.getConfiguration().getSecrets().get("apiKey").isConfigured(),
        "the edited projection must report the saved secret so the editor never falls back to"
            + " the pre-save state");
    assertTrue(
        connected.getConfiguration().getRevision().equals(connected.getDesiredRevision())
            && !connected.getConfiguration().getRevision().equals(connected.getAppliedRevision()),
        "the configuration projection follows the desired revision while applied lags behind");

    // CLEAR 掉唯一声明的秘密字段：删除凭据行的路径同样不需要主密钥。
    long clearedEpoch = catalog.readCatalog().epoch();
    long clearedRevision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();
    keylessService.updateConfiguration(
        STATIC_KEY_PLUGIN_ID,
        clearedEpoch,
        clearedRevision,
        "{\"endpoint\":\"https://api.example.com\"}",
        Map.of("apiKey", PluginSecretPatch.clear()));

    assertNull(credentials.getDirect(STATIC_KEY_PLUGIN_ID), "CLEAR must delete the row");
    assertEquals(
        List.of(),
        catalog
            .readConfiguration(
                STATIC_KEY_PLUGIN_ID,
                catalog
                    .readCatalog()
                    .manifest()
                    .plugins()
                    .get(STATIC_KEY_PLUGIN_ID)
                    .configRevision())
            .orElseThrow()
            .configuredSecretFields(),
        "the cleared revision must record no configured secret field");
    assertFalse(
        keylessService
            .getPlugin(STATIC_KEY_PLUGIN_ID)
            .getConfiguration()
            .getSecrets()
            .get("apiKey")
            .isConfigured(),
        "a cleared secret must be reported as not configured");
  }

  /** 未安装 Plugin 的配置提交与未知秘密字段都必须被拒。 */
  @Test
  void updateConfigurationRejectsUnknownPluginAndUnknownSecretField() {
    long epoch = catalog.readCatalog().epoch();
    long revision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();

    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateConfiguration("uninstalled-plugin", epoch, revision, "{}", Map.of()));
    AiValidationException unknownSecretField =
        assertThrows(
            AiValidationException.class,
            () ->
                service.updateConfiguration(
                    STATIC_KEY_PLUGIN_ID,
                    epoch,
                    revision,
                    "{\"endpoint\":\"https://api.example.com\"}",
                    Map.of("unknownField", PluginSecretPatch.set("x"))));
    assertFalse(
        unknownSecretField.getMessage().contains("unknownField"),
        "the error must not echo the caller supplied field name");
    assertFalse(unknownSecretField.toString().contains("unknownField"));
  }

  /** Plugin 没有认证能力时绝不探测凭据表或主密钥：任何探测都会让这个抛异常的 store 直接爆掉用例。 */
  @Test
  void noneAuthPluginNeverProbesTheCredentialStore() {
    PluginCredentialStore throwing =
        new PluginCredentialStore() {
          @Override
          public PluginCredentialProjection projection(String pluginId) {
            throw new AssertionError("a plugin without auth capability must not probe credentials");
          }

          @Override
          public PluginCredentialSnapshot resolve(PluginCredentialReference reference) {
            throw new AssertionError(
                "a plugin without auth capability must not resolve credentials");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            throw new AssertionError(
                "a plugin without auth capability must not reject credentials");
          }
        };
    PluginManagementService probingService =
        new PluginManagementService(
            registry,
            () -> snapshot,
            catalog,
            throwing,
            commitService,
            attempts,
            Duration.ofMinutes(10),
            clock);

    PluginDTO dto = probingService.getPlugin(NO_AUTH_PLUGIN_ID);
    assertNull(dto.getAuthKind());
    assertNull(dto.getStatus());
    assertNull(dto.getRegion());
    assertEquals("{}", dto.getConfiguration().getValuesJson());
    assertEquals(
        "1", dto.getConfiguration().getRevision(), "desired revision comes from the catalog");
  }

  /** 保存后编辑视图必须跟随 desired 历史：普通值、修订与已录入字段都取权威行，applied 坐标单独滞后。 */
  @Test
  void configurationProjectionFollowsTheDesiredRevision() {
    String savedValues = "{\"endpoint\":\"https://saved.example.com\"}";
    PluginDTO saved =
        service.updateConfiguration(
            STATIC_KEY_PLUGIN_ID,
            catalog.readCatalog().epoch(),
            catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision(),
            savedValues,
            Map.of("apiKey", PluginSecretPatch.set(SECRET_TOKEN_MARKER)));

    assertEquals(saved.getDesiredRevision(), saved.getConfiguration().getRevision());
    assertEquals(
        saved.getDesiredRevision(),
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision() + "",
        "the projection reports the just-saved revision as desired");
    assertEquals(savedValues, saved.getConfiguration().getValuesJson());
    assertTrue(saved.getConfiguration().getSecrets().get("apiKey").isConfigured());
    assertNotEquals(
        saved.getAppliedRevision(),
        saved.getConfiguration().getRevision(),
        "this node has not applied the save yet, so applied must lag behind");
    assertEquals(
        PluginConfigurationCommitService.PENDING_APPLICATION_ERROR, saved.getApplicationError());
  }

  /** 历史行缺失或与 manifest 坐标不一致时，投影与提交都必须 fail closed，绝不退回空字段或旧 revision。 */
  @Test
  void driftedConfigurationHistoryFailsClosedForProjectionAndCommit() {
    long epoch = catalog.readCatalog().epoch();
    long revision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();

    // 1. 历史行缺失。
    catalog.removeHistory(STATIC_KEY_PLUGIN_ID, revision);
    assertThrows(IllegalStateException.class, () -> service.getPlugin(STATIC_KEY_PLUGIN_ID));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.updateConfiguration(
                STATIC_KEY_PLUGIN_ID, epoch, revision, "{\"endpoint\":\"https://a\"}", Map.of()));

    // 2. 历史行存在但身份与 manifest 不一致（上下文指纹被改）。
    catalog.putHistory(driftedHistoryRow(STATIC_KEY_PLUGIN_ID, revision));
    assertThrows(IllegalStateException.class, () -> service.getPlugin(STATIC_KEY_PLUGIN_ID));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.updateConfiguration(
                STATIC_KEY_PLUGIN_ID, epoch, revision, "{\"endpoint\":\"https://a\"}", Map.of()));
  }

  /** 凭据行的条件写未命中（并发刷新/重新登录）必须以版本冲突失败，绝不覆盖或重试。 */
  @Test
  void credentialConditionalWriteMissReportsVersionConflict() {
    InMemoryPluginCredentialRepository failing =
        new InMemoryPluginCredentialRepository() {
          @Override
          public synchronized boolean insert(PluginCredentialRow row) {
            return false;
          }

          @Override
          public synchronized boolean replace(
              PluginCredentialRow row,
              UUID authGeneration,
              String contextFingerprint,
              long version) {
            return false;
          }
        };
    PluginConfigurationCommitService failingCommits =
        new PluginConfigurationCommitService(
            registry,
            catalog,
            failing,
            new PluginCredentialCodec(),
            keyLoader(),
            new PluginContextFactory(credentialStore, () -> null),
            List::of,
            List::of,
            transactions,
            clock,
            () -> {});

    assertThrows(
        AiVersionConflictException.class,
        () ->
            failingCommits.configure(
                STATIC_KEY_PLUGIN_ID,
                catalog.readCatalog().epoch(),
                catalog
                    .readCatalog()
                    .manifest()
                    .plugins()
                    .get(STATIC_KEY_PLUGIN_ID)
                    .configRevision(),
                "{\"endpoint\":\"https://api.example.com\"}",
                Map.of("apiKey", PluginSecretPatch.set(SECRET_TOKEN_MARKER))));
    assertNull(
        failing.getDirect(STATIC_KEY_PLUGIN_ID),
        "a failed conditional write must not leave a credential row");
  }

  /** revoke（断连）提交之后，旧的 pending attempt 既不能被消费，也绝不允许调用 Plugin 的完成 handler。 */
  @Test
  void revokedPendingAttemptIsNeverConsumedAndNeverCallsTheHandler() {
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_CN, now.plusSeconds(3600), now.plusSeconds(1800), "{\"token\":\"late\"}"));
    PluginAuthAttemptRow pending = attempts.find(PLUGIN_ID).orElse(null);
    assertNull(pending, "no attempt before prepare");
    String handle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();

    // revoke：断连提交推进 epoch 并改变该 Plugin 的配置修订。
    service.disconnect(PLUGIN_ID);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.completeAuth(PLUGIN_ID, handle, "https://callback.com/ok"));
    assertEquals(
        0,
        authPlugin.completeInvocations(),
        "a revoked coordinate must never reach the plugin completion handler");
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());
  }

  private PluginCredentialKeyLoader keyLoader() {
    return new PluginCredentialKeyLoader(
        tempDir.resolve("mgmt-test.key").toAbsolutePath().toString());
  }

  /** 身份与 manifest 不一致的历史行：上下文指纹被替换。 */
  private static PluginConfigurationRow driftedHistoryRow(String pluginId, long revision) {
    return new PluginConfigurationRow(
        pluginId,
        revision,
        "1.0",
        "a".repeat(64),
        "b".repeat(64),
        UUID.fromString("99999999-9999-9999-9999-999999999999"),
        "f".repeat(64),
        List.of(),
        "{}");
  }

  private void completeAuthOfManagedPlugin(String payloadJson) {
    String handle = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_CN, now.plusSeconds(3600), now.plusSeconds(1800), payloadJson));
    service.completeAuth(PLUGIN_ID, handle, "https://callback.com/valid");
  }

  /** 遍历整条 cause/suppressed 链，判断是否有任意 Throwable 的 message/toString 泄漏标记。 */
  private static boolean leaksMarker(Throwable root, String marker) {
    Throwable current = root;
    while (current != null) {
      if (current.getMessage() != null && current.getMessage().contains(marker)) {
        return true;
      }
      if (current.toString().contains(marker)) {
        return true;
      }
      for (Throwable suppressed : current.getSuppressed()) {
        if (leaksMarker(suppressed, marker)) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  /** 构建期上下文桩：没有资源端口，凭据如实失败关闭。 */
  private static final class PluginCredentialAccessStub implements PluginCredentialAccess {

    private final PluginCredentialReference reference;

    PluginCredentialAccessStub(PluginCredentialReference reference) {
      this.reference = reference;
    }

    @Override
    public PluginCredentialSnapshot resolve() {
      throw new PluginCredentialUnavailableException(
          PluginCredentialUnavailableReason.NOT_CONNECTED, "test context " + reference.pluginId());
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return false;
    }
  }

  // --- 测试辅助桩对象 ---

  private static class TestAuthPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(PLUGIN_ID, "Auth Plugin", "1.0");

    private Function<String, String> loginUrlSupplier =
        region -> "https://login.example.com/" + region;

    private Function<String, PluginCredentialMaterial> completeHandler = url -> null;

    /** 记录 handler 实际被调用的次数：任何被门禁拒绝的迟到回调都不得调用它。 */
    private int completeInvocations;

    int completeInvocations() {
      return completeInvocations;
    }

    void setLoginUrlSupplier(Function<String, String> loginUrlSupplier) {
      this.loginUrlSupplier = loginUrlSupplier;
    }

    void setCompleteHandler(Function<String, PluginCredentialMaterial> completeHandler) {
      this.completeHandler = completeHandler;
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.EMPTY_SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.DeepLink(List.of(REGION_CN, REGION_US));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(
          new PluginAuthHandler() {
            @Override
            public String loginUrl(String region) {
              return loginUrlSupplier.apply(region);
            }

            @Override
            public PluginCredentialMaterial complete(String callbackUrl) {
              completeInvocations++;
              return completeHandler.apply(callbackUrl);
            }
          });
    }
  }

  private static class TestNoAuthPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(NO_AUTH_PLUGIN_ID, "No Auth Plugin", "1.0");

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.EMPTY_SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }

  /** 静态密钥 Plugin：一个声明秘密字段加一个普通目标字段，用于验证秘密提交与主密钥惰性。 */
  private static class TestStaticKeyPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(STATIC_KEY_PLUGIN_ID, "Static Key Plugin", "1.0");

    private final PluginConfigSchema schema =
        new PluginConfigSchema(
            new InputSchema(null, Map.of("endpoint", new StringSchema(null)), Set.of(), false),
            "{\"endpoint\":\"https://default.example.com\"}",
            Map.of("apiKey", "static API key"));

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return schema;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of("endpoint"));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }

  @SuppressWarnings("unused")
  private static Supplier<PluginCatalogSnapshot> snapshotSupplier(PluginCatalogSnapshot snapshot) {
    return () -> snapshot;
  }
}
