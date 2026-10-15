package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
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
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
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
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

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
  private static final String CONTRIBUTING_PLUGIN_ID = "contributing-plugin";
  private static final String CONNECTION_CHECK_PLUGIN_ID = "connection-check-plugin";
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
  private PluginCatalogRuntime runtime;
  private StudioPluginRegistry registry;
  private PluginManagementService service;
  private TestAuthPlugin authPlugin;
  private TestStaticKeyPlugin staticKeyPlugin;
  private TestContributingPlugin contributingPlugin;
  private TestConnectionCheckPlugin connectionCheckPlugin;

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
    contributingPlugin = new TestContributingPlugin();
    connectionCheckPlugin = new TestConnectionCheckPlugin();
    registry =
        new StudioPluginRegistry(
            List.of(
                authPlugin,
                new TestNoAuthPlugin(),
                staticKeyPlugin,
                contributingPlugin,
                connectionCheckPlugin));

    // 用真实启动装配建立 epoch 1 的 manifest 与配置历史：管理面与提交服务都只看权威行。
    PluginCatalogBootstrap bootstrap = new PluginCatalogBootstrap(registry, catalog, transactions);
    BiFunction<String, PluginCredentialReference, PluginContext> contexts =
        (pluginId, reference) ->
            new PluginContext(new PluginCredentialAccessStub(reference), Optional.empty());
    snapshot = bootstrap.bootstrap(contexts, List.of(), List.of());
    // 运行期所有者唯一拥有该快照：管理面每次投影向它取一次 current 租用。
    runtime =
        new PluginCatalogRuntime(bootstrap, catalog, contexts, List.of(), List.of(), snapshot);

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
        () -> {},
        () -> {});
  }

  private PluginManagementService service(
      DatabasePluginCredentialStore store, PluginConfigurationCommitService commits) {
    return new PluginManagementService(
        registry, () -> runtime, catalog, store, commits, attempts, Duration.ofMinutes(10), clock);
  }

  /** 运行期所有者按测试关闭其唯一初始快照，避免实例泄漏。 */
  @AfterEach
  void closeRuntime() {
    runtime.close();
  }

  /** listPlugins() 顺序与 registry 一致；DTO 绝不泄漏敏感密钥或回调参数。 */
  @Test
  void listPluginsOrderMatchesRegistryAndExcludesSecretMarkers() {
    completeAuthOfManagedPlugin("{\"token\":\"" + SECRET_TOKEN_MARKER + "\"}");

    List<PluginDTO> dtos = service.listPlugins();
    assertEquals(5, dtos.size(), "registry now carries the explicit connection check plugin too");
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
    // 形态非法的句柄：在摘要与消费之前就以固定校验失败拒绝，绝不 500、绝不回显原文。
    AiValidationException malformed =
        assertThrows(
            AiValidationException.class,
            () ->
                service.completeAuth(
                    PLUGIN_ID, "MALFORMED_ATTEMPT_VALUE", "https://callback.com/ok"));
    assertFalse(malformed.getMessage().contains("MALFORMED_ATTEMPT_VALUE"));
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, "a".repeat(2000), "https://callback.com/ok"));
    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, PluginAuthNonce.issue() + "=", "https://cb.com/ok"));

    String validButUnused = service.prepareAuth(PLUGIN_ID, REGION_CN).getAuthAttemptId();
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, validButUnused, null));
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, validButUnused, "   "));

    // 未经 prepare 的合法形态句柄：不得消费、不得落库，且错误里不含句柄原文。
    String unknownHandle = PluginAuthNonce.issue();
    AiValidationException unknown =
        assertThrows(
            AiValidationException.class,
            () -> service.completeAuth(PLUGIN_ID, unknownHandle, "https://callback.com/ok"));
    assertFalse(unknown.getMessage().contains(unknownHandle));
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
            () -> runtime,
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
            () -> runtime,
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
            () -> {},
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

  /** 静态认证的状态投影只约束 {@code CONNECTED}：同一份秘密行只承载可选私有字段时，真实故障态（主密钥不可用）绝不被改写成未连接。 */
  @Test
  void staticKeyPreservesKeyUnavailableWhenOnlyThePrivateSecretIsConfigured() {
    long epoch = catalog.readCatalog().epoch();
    long revision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();
    service.updateConfiguration(
        STATIC_KEY_PLUGIN_ID,
        epoch,
        revision,
        "{\"endpoint\":\"https://api.example.com\"}",
        Map.of("instanceId", PluginSecretPatch.set("private-identity-7")));

    long configuredRevision =
        catalog.readCatalog().manifest().plugins().get(STATIC_KEY_PLUGIN_ID).configRevision();
    assertEquals(
        List.of("instanceId"),
        catalog
            .readConfiguration(STATIC_KEY_PLUGIN_ID, configuredRevision)
            .orElseThrow()
            .configuredSecretFields(),
        "only the optional private secret field is recorded, the auth key is still missing");

    assertEquals(
        PluginCredentialStatus.KEY_UNAVAILABLE.name(),
        serviceWithProjection(PluginCredentialProjection.keyUnavailable())
            .getPlugin(STATIC_KEY_PLUGIN_ID)
            .getStatus(),
        "the missing auth key must not mask a key failure that is reported by the credential store");
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED.name(),
        serviceWithProjection(
                new PluginCredentialProjection(
                    PluginCredentialStatus.CONNECTED, null, null, null, null, null))
            .getPlugin(STATIC_KEY_PLUGIN_ID)
            .getStatus(),
        "a credential row without the required auth field must never be reported as connected");
  }

  /** 只固定投影状态的 store：状态投影分支不依赖真实主密钥环境，也不得顺带解析或作废凭据。 */
  private PluginManagementService serviceWithProjection(PluginCredentialProjection projection) {
    PluginCredentialStore store =
        new PluginCredentialStore() {
          @Override
          public PluginCredentialProjection projection(String pluginId) {
            return projection;
          }

          @Override
          public PluginCredentialSnapshot resolve(PluginCredentialReference reference) {
            throw new AssertionError("a status projection must not resolve credentials");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            throw new AssertionError("a status projection must not reject credentials");
          }
        };
    return new PluginManagementService(
        registry,
        () -> runtime,
        catalog,
        store,
        commitService,
        attempts,
        Duration.ofMinutes(10),
        clock);
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

  /**
   * 一次 getPlugin / listPlugins 各自只读一次 desired catalog 与一次 applied 运行期：同一份响应绝不混用两个 epoch 的坐标、配置与能力。
   */
  @Test
  void oneProjectionReadsOneDesiredCatalogAndOneAppliedRuntime() {
    AtomicInteger catalogReads = new AtomicInteger();
    AtomicInteger runtimeReads = new AtomicInteger();
    InMemoryPluginConfigurationRepository countingCatalog =
        new InMemoryPluginConfigurationRepository() {
          @Override
          public PluginCatalogRecord readCatalog() {
            catalogReads.incrementAndGet();
            return super.readCatalog();
          }

          @Override
          public Optional<PluginConfigurationRow> readConfiguration(
              String pluginId, long revision) {
            return catalog.readConfiguration(pluginId, revision);
          }
        };
    countingCatalog.initialize(catalog.readCatalog());
    PluginManagementService counting =
        new PluginManagementService(
            registry,
            () -> {
              runtimeReads.incrementAndGet();
              return runtime;
            },
            countingCatalog,
            credentialStore,
            commitService,
            attempts,
            Duration.ofMinutes(10),
            clock);

    counting.getPlugin(PLUGIN_ID);
    assertEquals(
        1, catalogReads.get(), "one plugin projection must read the desired catalog exactly once");
    assertEquals(
        1,
        runtimeReads.get(),
        "one plugin projection must consult the applied runtime exactly once");

    List<PluginDTO> dtos = counting.listPlugins();
    assertEquals(registry.plugins().size(), dtos.size());
    assertEquals(
        2, catalogReads.get(), "one list projection must read the desired catalog exactly once");
    assertEquals(
        2, runtimeReads.get(), "one list projection must consult the applied runtime exactly once");
  }

  /** 已应用能力名只来自 applied 快照的 owner map 与真实 definitions：没有两域贡献的 Plugin 必须为空列表，绝不手写能力清单。 */
  @Test
  void appliedCapabilityNamesComeFromTheAppliedSnapshot() {
    PluginDTO contributing = service.getPlugin(CONTRIBUTING_PLUGIN_ID);
    assertEquals(List.of("contributing-tool"), contributing.getHarnessToolNames());
    assertEquals(List.of("contributing-function"), contributing.getCanvasFunctionNames());

    PluginDTO none = service.getPlugin(NO_AUTH_PLUGIN_ID);
    assertEquals(List.of(), none.getHarnessToolNames());
    assertEquals(List.of(), none.getCanvasFunctionNames());
  }

  /** 投影必须释放 applied 租用：只要 getPlugin 与 listPlugins 泄漏一个租用，运行期关闭后其唯一快照就不会被退役，冻结实例也就不会关闭。 */
  @Test
  void projectionsReleaseTheAppliedLeaseSoTheRuntimeRetiresItsSnapshot() {
    service.getPlugin(CONTRIBUTING_PLUGIN_ID);
    service.listPlugins();

    runtime.close();

    assertEquals(
        1,
        contributingPlugin.instancesClosed.get(),
        "the only snapshot owner must retire exactly once after every projection released its lease");
  }

  /** 保存后的 desired 与旧 applied 能力并列显示；对账后能力、epoch 和 revision 一起切换。 */
  @Test
  void capabilitiesSwitchWithTheAppliedEpochAfterRefresh() {
    PluginDTO pending =
        service.updateConfiguration(
            CONTRIBUTING_PLUGIN_ID, 1L, 1L, "{\"enabled\":false}", Map.of());
    assertEquals("2", pending.getDesiredEpoch());
    assertEquals("1", pending.getAppliedEpoch());
    assertEquals("1", pending.getAppliedRevision());
    assertEquals(List.of("contributing-tool"), pending.getHarnessToolNames());
    assertEquals(List.of("contributing-function"), pending.getCanvasFunctionNames());
    assertEquals(
        PluginConfigurationCommitService.PENDING_APPLICATION_ERROR, pending.getApplicationError());

    runtime.refresh();
    PluginDTO applied = service.getPlugin(CONTRIBUTING_PLUGIN_ID);
    assertEquals("2", applied.getAppliedEpoch());
    assertEquals("2", applied.getAppliedRevision());
    assertEquals(List.of(), applied.getHarnessToolNames());
    assertEquals(List.of(), applied.getCanvasFunctionNames());
    assertNull(applied.getApplicationError());
  }

  /**
   * applicationError 只在运行期记录的错误与本次投影的 applied 租用、权威 desired 同属一个 epoch 时才报真实固定码， 否则如实挂起；一旦权威对账成功应用
   * desired，即恢复为 null，绝不是永久 pending。
   */
  @Test
  void applicationErrorReportsTheRealFailureForTheSameEpochOnly() {
    PluginCatalogRecord original = catalog.readCatalog();
    PluginCatalogManifest originalManifest = original.manifest();

    // 1. 权威推进到 epoch 2，但 manifest 的全局目录指纹与真实冻结目录不一致：本节点对账无法应用。
    assertTrue(
        catalog.commit(
            original.epoch(),
            List.of(),
            new PluginCatalogManifest(originalManifest.plugins(), "0".repeat(64))));
    runtime.refresh();

    assertEquals(
        new PluginCatalogRuntime.RuntimeState(
            original.epoch() + 1, original.epoch(), PluginCatalogRuntime.ERROR_APPLY_FAILED),
        runtime.state());

    PluginDTO failed = service.getPlugin(PLUGIN_ID);
    assertEquals(Long.toString(original.epoch() + 1), failed.getDesiredEpoch());
    assertEquals(Long.toString(original.epoch()), failed.getAppliedEpoch());
    assertEquals(
        PluginCatalogRuntime.ERROR_APPLY_FAILED,
        failed.getApplicationError(),
        "the real fixed apply failure code must be reported, not a permanent pending");

    // 2. 权威再次前进到 epoch 3：运行期错误仍属于 epoch 2，绝不伪装成 epoch 3 的投影状态。
    assertTrue(
        catalog.commit(
            original.epoch() + 1,
            List.of(),
            new PluginCatalogManifest(
                originalManifest.plugins(), originalManifest.catalogFingerprint())));
    PluginDTO drifted = service.getPlugin(PLUGIN_ID);
    assertEquals(Long.toString(original.epoch() + 2), drifted.getDesiredEpoch());
    assertEquals(
        PluginConfigurationCommitService.PENDING_APPLICATION_ERROR,
        drifted.getApplicationError(),
        "an error recorded for another epoch must not be disguised as this projection's state");

    // 3. 对账成功应用 epoch 3：恢复同步后不再挂起。
    runtime.refresh();
    assertEquals(
        new PluginCatalogRuntime.RuntimeState(original.epoch() + 2, original.epoch() + 2, null),
        runtime.state());
    PluginDTO applied = service.getPlugin(PLUGIN_ID);
    assertEquals(applied.getDesiredEpoch(), applied.getAppliedEpoch());
    assertNull(applied.getApplicationError());
  }

  /** 显式连接检查：只有请求坐标与当前 applied 坐标逐项一致才调用实例能力一次，返回值只含类型化维度与已应用坐标， 检查既不另建也不关闭任何实例。 */
  @Test
  void checkConnectionRequiresAppliedCoordinatesAndReturnsTypedResult() {
    assertTrue(
        service.getPlugin(CONNECTION_CHECK_PLUGIN_ID).isConnectionCheckSupported(),
        "能力必须来自已应用实例，而不是按 pluginId 硬编码");
    assertFalse(service.getPlugin(NO_AUTH_PLUGIN_ID).isConnectionCheckSupported());

    PluginConnectionCheckDTO dto = service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L);

    assertEquals(CONNECTION_CHECK_PLUGIN_ID, dto.getPluginId());
    assertEquals("1", dto.getAppliedEpoch());
    assertEquals("1", dto.getAppliedRevision());
    assertEquals(now, dto.getCheckedAt(), "检查时刻取自管理面时钟");
    assertEquals("REACHABLE", dto.getReachability());
    assertEquals("ACCEPTED", dto.getAuthentication());
    assertEquals("UNKNOWN", dto.getBrowserSession());
    assertEquals(1, connectionCheckPlugin.checkInvocations.get(), "一次检查只调用一次能力");

    // 检查只租用当前快照：不另建实例，期间也不关闭实例；租用释放后运行期关闭才真正退役实例。
    assertEquals(0, connectionCheckPlugin.instancesClosed.get());
    runtime.close();
    assertEquals(1, connectionCheckPlugin.instancesClosed.get(), "租用必须已释放，实例只关闭一次");
  }

  /** 测试意图：尚未应用或坐标不匹配时明确 409，且在任何远端动作之前就必须拒绝，绝不调用实例能力。 */
  @Test
  void checkConnectionRejectsPendingOrMismatchedCoordinatesWithoutTouchingThePlugin() {
    commitService.configure(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L, "{}", Map.of());

    // desired 已前进到 epoch 2，但本节点仍应用 epoch 1：pending 必须 409
    assertThrows(
        AiVersionConflictException.class,
        () -> service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L));
    // 期望 epoch 与已应用不一致
    assertThrows(
        AiVersionConflictException.class,
        () -> service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 2L, 1L));

    runtime.refresh();
    // 已应用 epoch 2 后，仍持有旧 epoch 与错误修订的请求必须 409
    assertThrows(
        AiVersionConflictException.class,
        () -> service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L));
    assertThrows(
        AiVersionConflictException.class,
        () -> service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 2L, 1L));

    assertEquals(0, connectionCheckPlugin.checkInvocations.get(), "坐标未命中不得触发任何检查");

    // 应用后的坐标完全命中才放行：revision 跟随写入时的全局 epoch
    PluginConnectionCheckDTO applied = service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 2L, 2L);
    assertEquals("2", applied.getAppliedEpoch());
    assertEquals("2", applied.getAppliedRevision());
    assertEquals(1, connectionCheckPlugin.checkInvocations.get());
  }

  /**
   * 租用生命周期：检查途中发布新目录时，正在执行检查的那个实例必须一直活着，只有租用释放后才真正关闭。
   *
   * <p>回调在同一线程内同步执行，观察的是真实租用语义而不是人为并发。注意提交服务为了算能力指纹会另行构建并关闭一份一次性候选快照，因此这里断言的是
   * <em>具体实例</em>的关闭状态，而不是插件级别的关闭计数。
   */
  @Test
  void connectionCheckKeepsTheCheckedInstanceAliveUntilTheLeaseIsReleased() {
    connectionCheckPlugin.onCheck =
        () -> {
          // 检查途中提交并发布新目录：执行中的实例仍被本次租用持有，必须存活
          commitService.configure(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L, "{}", Map.of());
          runtime.refresh();
          TestConnectionCheckInstance serving = connectionCheckPlugin.serving;
          assertNotNull(serving, "检查必须由真实实例执行");
          assertFalse(serving.closed, "检查执行期间该实例不得被关闭；租用未结束前旧视图不得退役");
        };

    service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L);

    TestConnectionCheckInstance served = connectionCheckPlugin.serving;
    assertTrue(served.closed, "检查返回后必须释放租用并关闭旧视图的实例");
    assertEquals(2L, runtime.state().appliedEpoch(), "新目录必须已经应用");
  }

  /** 测试意图：检查抛异常时同样必须释放租用，正在执行检查的实例不得因失败而泄漏。 */
  @Test
  void connectionCheckReleasesTheLeaseEvenWhenTheCheckFails() {
    connectionCheckPlugin.onCheck =
        () -> {
          commitService.configure(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L, "{}", Map.of());
          runtime.refresh();
          assertFalse(connectionCheckPlugin.serving.closed, "失败前租用仍然有效，实例不得被关闭");
          throw new IllegalStateException("unexpected check failure");
        };

    assertThrows(
        IllegalStateException.class,
        () -> service.checkConnection(CONNECTION_CHECK_PLUGIN_ID, 1L, 1L));

    assertTrue(connectionCheckPlugin.serving.closed, "检查失败后必须释放租用并关闭旧视图的实例");
  }

  /** 测试意图：未安装是 404，已安装但实例未声明能力是 400，两者都绝不静默成功。 */
  @Test
  void checkConnectionReportsUninstalledAndUnsupportedPlugins() {
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.checkConnection("uninstalled-plugin", 1L, 1L));
    assertThrows(
        AiValidationException.class, () -> service.checkConnection(NO_AUTH_PLUGIN_ID, 1L, 1L));
    assertEquals(0, connectionCheckPlugin.checkInvocations.get());
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

  /** 声明显式只读连接检查的 Plugin：检查调用与实例关闭都被计数，用于验证租用、坐标门禁与实例所有权。 */
  private static class TestConnectionCheckPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor =
        new PluginDescriptor(CONNECTION_CHECK_PLUGIN_ID, "Connection Check Plugin", "1.0");

    private final AtomicInteger checkInvocations = new AtomicInteger();
    private final AtomicInteger instancesClosed = new AtomicInteger();

    /** 检查回调钩子：让测试在租用仍然有效时观察实例生命周期。 */
    private volatile Runnable onCheck = () -> {};

    /** 最近一次真正执行检查的实例，用于断言具体实例的关闭时机。 */
    private volatile TestConnectionCheckInstance serving;

    private final PluginConnectionCheckResult result =
        new PluginConnectionCheckResult(
            PluginConnectionCheckResult.Reachability.REACHABLE,
            PluginConnectionCheckResult.Authentication.ACCEPTED,
            PluginConnectionCheckResult.BrowserSession.UNKNOWN);

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
      return new TestConnectionCheckInstance(this);
    }
  }

  /** 带生命周期观测的 Plugin 实例：每个快照实例各自记录是否已被关闭，避免与提交服务为算指纹而创建并关闭的一次性候选实例互相污染。 */
  private static final class TestConnectionCheckInstance implements PluginInstance {

    private final TestConnectionCheckPlugin owner;

    private volatile boolean closed;

    TestConnectionCheckInstance(TestConnectionCheckPlugin owner) {
      this.owner = owner;
    }

    @Override
    public List<HarnessContributor> harnessContributors() {
      return List.of();
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return List.of();
    }

    @Override
    public Optional<PluginConnectionCheck> connectionCheck() {
      return Optional.of(
          () -> {
            owner.checkInvocations.incrementAndGet();
            owner.serving = this;
            owner.onCheck.run();
            return owner.result;
          });
    }

    @Override
    public void close() {
      closed = true;
      owner.instancesClosed.incrementAndGet();
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
            Map.of("apiKey", "static API key", "instanceId", "optional private identity"));

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

  /** 同时贡献 Harness 工具与 Canvas 函数的 Plugin：用于验证 applied 能力名确实是快照派生的真实名称。 */
  private static class TestContributingPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(CONTRIBUTING_PLUGIN_ID, "Contributing Plugin", "1.0");

    /** 已关闭的冻结实例数：用于断言投影释放租用后运行期能真正退役其唯一快照。 */
    private final AtomicInteger instancesClosed = new AtomicInteger();

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return new PluginConfigSchema(
          new InputSchema(
              "config", Map.of("enabled", new BooleanSchema("enabled")), Set.of("enabled"), false),
          "{\"enabled\":true}",
          Map.of());
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      boolean enabled = JsonValues.readTree(config.valuesJson()).get("enabled").asBoolean();
      HarnessContributor contributor =
          HarnessContributor.of(
              new ContributorDescriptor(
                  new ContributorId(CONTRIBUTING_PLUGIN_ID), "contributing", "1.0", Set.of()),
              registrar ->
                  registrar.registerTool(
                      "contributing-tool", new ContributingTool(), ToolVisibility.SELECTABLE));
      CanvasFunctionAdapter adapter = contributingAdapter();
      return new PluginInstance() {
        @Override
        public List<HarnessContributor> harnessContributors() {
          return enabled ? List.of(contributor) : List.of();
        }

        @Override
        public List<CanvasFunctionAdapter> canvasAdapters() {
          return enabled ? List.of(adapter) : List.of();
        }

        @Override
        public void close() {
          instancesClosed.incrementAndGet();
        }
      };
    }
  }

  /** 只提供冻结定义的工具桩：管理面投影绝不执行工具。 */
  private static class ContributingTool implements Tool {
    @Override
    public ToolDescriptor descriptor() {
      return new ToolDescriptor(
          "contributing-tool",
          "contributing tool",
          "contributing-tool",
          new InputSchema(null, Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      throw new UnsupportedOperationException("the management projection never executes tools");
    }
  }

  private static CanvasFunctionAdapter contributingAdapter() {
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            "contributing-function",
            "function contributing-function",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionDefinition> functions() {
        return List.of(definition);
      }

      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return null;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        return List.of();
      }
    };
  }
}
