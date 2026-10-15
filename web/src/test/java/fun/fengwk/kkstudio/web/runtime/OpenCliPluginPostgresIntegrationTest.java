package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真实隔离 PostgreSQL 上 OpenCLI 插件的契约与生命周期集成测试。
 *
 * <p>验证： 1. 独占隔离数据库，runtime-only 插件通过反射加载配置； 2. 真实凭据加密落库，HTTP 请求携带 Bearer； 3. 密钥轮换演进宿主代际并 fence
 * 旧实例，新 lease 实例使用新密钥； 4. baseUrl 变更重新派生 contextFingerprint 并 fence 旧上下文； 5. 在途旧实例在 baseUrl
 * 变更或密钥撤销（CLEAR）后出网前 fail-closed； 6. 历史 epoch 恢复后旧代际实例在调用时 fail-closed。
 */
class OpenCliPluginPostgresIntegrationTest {

  private static final String PLUGIN_ID = "opencli";
  private static final String GPT_IMAGE_FUNCTION = "gpt-image-2";
  private static final String OPENCLI_PACKAGE_PREFIX = "fun.fengwk.kkstudio.plugin.";
  private static final String OPENCLI_AUTO_CONFIGURATION_NAME =
      OPENCLI_PACKAGE_PREFIX + "opencli.OpenCliAutoConfiguration";

  @TempDir Path tempDir;

  private DataSource dataSource;
  private JdbcClient jdbcClient;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private PluginCredentialCodec codec;
  private PluginCredentialKeyLoader keyLoader;
  private DatabasePluginCredentialStore credentialStore;
  private PlatformTransactionManager transactionManager;

  private final List<HttpServer> runningServers = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    jdbcClient = JdbcClient.create(dataSource);
    configurationRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
    credentialRepository =
        new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));
    codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("opencli-integration-master.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    try {
      Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // 兼容不支持 POSIX 文件权限的运行环境
    }
    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    credentialStore =
        new DatabasePluginCredentialStore(
            credentialRepository, configurationRepository, codec, keyLoader, Clock.systemUTC());
    transactionManager = new DataSourceTransactionManager(dataSource);
  }

  @AfterEach
  void tearDown() {
    for (HttpServer server : runningServers) {
      server.stop(0);
    }
    runningServers.clear();
  }

  @Test
  void bootstrapInitializesDisabledStateInIsolatedDatabase() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);

              Optional<StudioPlugin> plugin = registry.find(PLUGIN_ID);
              assertTrue(
                  plugin.isPresent(), "opencli plugin must be registered via auto-configuration");
              assertEquals("OpenCLI", plugin.get().descriptor().name());
              assertEquals("1.0.0", plugin.get().descriptor().version());

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(1L, snapshot.epoch(), "first bootstrap must advance epoch to 1");

                CanvasFunctionCatalog catalog = snapshot.canvasFunctionCatalog();
                assertEquals(
                    5, catalog.list().size(), "opencli must contribute 5 canvas functions");
                for (CanvasFunctionCatalog.RegisteredFunction fn : catalog.list()) {
                  assertFalse(fn.enabled(), "functions must be disabled by default");
                  assertNotNull(fn.unavailableReason(), "unavailableReason must be specified");
                }
              }

              assertEquals(1L, readEpoch(jdbcClient));
              assertEquals(1, countConfigurationRevisions(jdbcClient));
              assertTrue(
                  credentialRepository.find(PLUGIN_ID).isEmpty(), "no credentials at bootstrap");
            });
  }

  @Test
  void secretSetPersistsEncryptedCredentialAndSendsBearerOverRealHttp() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    String secretKey = "synthetic-opencli-key-13579";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              String valuesJson =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrl
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";

              commitService.configure(
                  PLUGIN_ID,
                  1L,
                  1L,
                  valuesJson,
                  Map.of("apiKey", PluginSecretPatch.set(secretKey)));
              runtime.refresh();

              // 验证真实 PostgreSQL 落库
              assertEquals(2L, readEpoch(jdbcClient));
              PluginCredentialRow credentialRow =
                  credentialRepository.find(PLUGIN_ID).orElseThrow();
              assertEquals(PluginCredentialStatus.CONNECTED, credentialRow.status());
              assertNotNull(credentialRow.authGeneration());
              assertNotNull(credentialRow.contextFingerprint());
              assertFalse(
                  containsBytes(
                      credentialRow.encryptedPayload(), secretKey.getBytes(StandardCharsets.UTF_8)),
                  "plaintext secret must not exist in database");

              PluginConfigurationRow history =
                  configurationRepository.readConfiguration(PLUGIN_ID, 2L).orElseThrow();
              assertEquals(List.of("apiKey"), history.configuredSecretFields());

              // 验证当前快照与真实 HTTP 出网
              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(2L, snapshot.epoch());

                CanvasFunctionCatalog.RegisteredFunction registered =
                    snapshot.canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION);
                assertTrue(registered.enabled());
                assertNull(registered.unavailableReason());

                CanvasFunctionAdapter adapter = registered.adapter();
                CanvasFunctionExecutionContext executionContext =
                    mock(CanvasFunctionExecutionContext.class);
                when(executionContext.isRunning()).thenReturn(true);
                CanvasFunctionFrozenRun run =
                    createTestRun(2L, adapter.functions().get(0), "generate portrait");

                adapter.submit(executionContext, run);

                assertEquals(1, requests.size(), "one HTTP request must reach local server");
                RecordedRequest request = requests.get(0);
                assertEquals("POST", request.method());
                assertEquals("/api/opencli/execute", request.path());
                assertEquals("Bearer " + secretKey, request.authorization());
              }
            });
  }

  @Test
  void secretRotationAdvancesGenerationFencesOldInstanceAndAppliesNewKey() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    String keyV1 = "synthetic-key-generation-alpha";
    String keyV2 = "synthetic-key-generation-beta";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              String valuesJson =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrl
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";

              // 1. 设置 keyV1
              commitService.configure(
                  PLUGIN_ID, 1L, 1L, valuesJson, Map.of("apiKey", PluginSecretPatch.set(keyV1)));
              runtime.refresh();

              UUID generationV1 =
                  credentialRepository.find(PLUGIN_ID).orElseThrow().authGeneration();

              CanvasFunctionExecutionContext execContext =
                  mock(CanvasFunctionExecutionContext.class);
              when(execContext.isRunning()).thenReturn(true);

              // 2. 租用并持有旧实例 leaseV1
              try (PluginCatalogRuntime.Lease leaseV1 = runtime.acquireCurrent()) {
                CanvasFunctionAdapter oldAdapter =
                    leaseV1
                        .snapshot()
                        .canvasFunctionCatalog()
                        .require(GPT_IMAGE_FUNCTION)
                        .adapter();

                oldAdapter.submit(
                    execContext, createTestRun(2L, oldAdapter.functions().get(0), "prompt 1"));
                assertEquals(1, requests.size());
                assertEquals("Bearer " + keyV1, requests.get(0).authorization());

                // 3. 轮换为 keyV2
                commitService.configure(
                    PLUGIN_ID, 2L, 2L, valuesJson, Map.of("apiKey", PluginSecretPatch.set(keyV2)));
                runtime.refresh();

                PluginCredentialRow rowV2 = credentialRepository.find(PLUGIN_ID).orElseThrow();
                assertNotEquals(
                    generationV1, rowV2.authGeneration(), "auth generation must rotate");

                // 4. 验证旧实例被 fence，拒绝出网
                CanvasFunctionFrozenRun staleRun =
                    createTestRun(2L, oldAdapter.functions().get(0), "prompt with stale lease");
                assertThrows(
                    CanvasFunctionUnknownException.class,
                    () -> oldAdapter.submit(execContext, staleRun),
                    "old instance with stale auth generation must be fenced");
                assertEquals(
                    1, requests.size(), "fenced old instance must not emit network request");

                // 5. 获取新 lease，新实例绑定新 generation 并使用 keyV2 出网
                try (PluginCatalogRuntime.Lease leaseV2 = runtime.acquireCurrent()) {
                  CanvasFunctionAdapter newAdapter =
                      leaseV2
                          .snapshot()
                          .canvasFunctionCatalog()
                          .require(GPT_IMAGE_FUNCTION)
                          .adapter();
                  newAdapter.submit(
                      execContext, createTestRun(3L, newAdapter.functions().get(0), "prompt 2"));

                  assertEquals(2, requests.size());
                  assertEquals("Bearer " + keyV2, requests.get(1).authorization());
                }
              }
            });
  }

  @Test
  void baseUrlChangeRegeneratesAuthContextFingerprintAndFencesOldContext() throws IOException {
    List<RecordedRequest> requestsA = new CopyOnWriteArrayList<>();
    List<RecordedRequest> requestsB = new CopyOnWriteArrayList<>();
    HttpServer serverA = createControlledServer(requestsA);
    HttpServer serverB = createControlledServer(requestsB);
    String baseUrlA = "http://127.0.0.1:" + serverA.getAddress().getPort();
    String baseUrlB = "http://127.0.0.1:" + serverB.getAddress().getPort();
    String key = "persistent-key-across-hosts";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              // 1. 在 baseUrlA 上配置并提交 key
              String valuesA =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrlA
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";
              commitService.configure(
                  PLUGIN_ID, 1L, 1L, valuesA, Map.of("apiKey", PluginSecretPatch.set(key)));
              runtime.refresh();

              PluginCredentialRow rowA = credentialRepository.find(PLUGIN_ID).orElseThrow();
              UUID generationA = rowA.authGeneration();
              String contextFingerprintA = rowA.contextFingerprint();
              PluginCredentialReference refA =
                  new PluginCredentialReference(PLUGIN_ID, generationA, contextFingerprintA);

              // 2. 更改 baseUrl 为 baseUrlB 并 KEEP 密钥
              String valuesB =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrlB
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";
              commitService.configure(
                  PLUGIN_ID, 2L, 2L, valuesB, Map.of("apiKey", PluginSecretPatch.keep()));
              runtime.refresh();

              PluginCredentialRow rowB = credentialRepository.find(PLUGIN_ID).orElseThrow();
              UUID generationB = rowB.authGeneration();
              String contextFingerprintB = rowB.contextFingerprint();
              PluginCredentialReference refB =
                  new PluginCredentialReference(PLUGIN_ID, generationB, contextFingerprintB);

              assertNotEquals(
                  generationA, generationB, "auth generation must rotate when context changes");
              assertNotEquals(
                  contextFingerprintA,
                  contextFingerprintB,
                  "baseUrl is a scopeKey, so contextFingerprint must change");

              // 旧引用无法再从凭据库中读取（STALE_REFERENCE_ERROR / fail-closed）
              assertThrows(
                  PluginCredentialUnavailableException.class,
                  () -> credentialStore.resolve(refA),
                  "old context reference must be rejected");

              // 新引用能正常解密出原密钥
              assertEquals(
                  "{\"apiKey\":\"" + key + "\"}",
                  credentialStore.resolve(refB).payloadJson(),
                  "re-encrypted credential under new context must yield same secret");
            });
  }

  @Test
  void inFlightInstanceFencingRejectsNetworkEgressOnBaseUrlChangeOrRevocation() throws IOException {
    List<RecordedRequest> requestsA = new CopyOnWriteArrayList<>();
    List<RecordedRequest> requestsB = new CopyOnWriteArrayList<>();
    HttpServer serverA = createControlledServer(requestsA);
    HttpServer serverB = createControlledServer(requestsB);
    String baseUrlA = "http://127.0.0.1:" + serverA.getAddress().getPort();
    String baseUrlB = "http://127.0.0.1:" + serverB.getAddress().getPort();
    String key = "fenced-instance-secret-key";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              String valuesA =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrlA
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";
              commitService.configure(
                  PLUGIN_ID, 1L, 1L, valuesA, Map.of("apiKey", PluginSecretPatch.set(key)));
              runtime.refresh();

              CanvasFunctionExecutionContext execContext =
                  mock(CanvasFunctionExecutionContext.class);
              when(execContext.isRunning()).thenReturn(true);

              // 租用并持有旧实例 leaseA
              try (PluginCatalogRuntime.Lease leaseA = runtime.acquireCurrent()) {
                CanvasFunctionAdapter oldAdapter =
                    leaseA.snapshot().canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION).adapter();

                // 验证旧实例首次正常出网
                CanvasFunctionFrozenRun run1 =
                    createTestRun(2L, oldAdapter.functions().get(0), "in-flight prompt 1");
                oldAdapter.submit(execContext, run1);
                assertEquals(1, requestsA.size());
                assertEquals(0, requestsB.size());

                // 后台提交更新：切换 baseUrl 至 Server B
                String valuesB =
                    "{\"enabled\":true,\"baseUrl\":\""
                        + baseUrlB
                        + "\",\"gptImage2\":{\"paidEnabled\":true}}";
                commitService.configure(
                    PLUGIN_ID, 2L, 2L, valuesB, Map.of("apiKey", PluginSecretPatch.keep()));
                runtime.refresh();

                // 尝试继续使用旧实例发送请求：出网前被拦截
                CanvasFunctionFrozenRun run2 =
                    createTestRun(2L, oldAdapter.functions().get(0), "in-flight prompt 2");
                assertThrows(
                    CanvasFunctionUnknownException.class,
                    () -> oldAdapter.submit(execContext, run2),
                    "old instance must fail when its credentials are fenced");

                assertEquals(1, requestsA.size(), "no new network request should reach server A");
                assertEquals(0, requestsB.size(), "old instance must never reach server B");
              }
            });
  }

  @Test
  void secretClearDeletesCredentialRowAndFencesAllSubsequentEgress() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    String key = "secret-to-be-cleared";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              String values =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrl
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";

              // 1. 设置密钥并发布
              commitService.configure(
                  PLUGIN_ID, 1L, 1L, values, Map.of("apiKey", PluginSecretPatch.set(key)));
              runtime.refresh();
              assertTrue(credentialRepository.find(PLUGIN_ID).isPresent());

              CanvasFunctionExecutionContext execContext =
                  mock(CanvasFunctionExecutionContext.class);
              when(execContext.isRunning()).thenReturn(true);

              // 2. CLEAR 前租用并保留旧 lease，验证旧实例正常出网
              try (PluginCatalogRuntime.Lease oldLease = runtime.acquireCurrent()) {
                CanvasFunctionAdapter oldAdapter =
                    oldLease
                        .snapshot()
                        .canvasFunctionCatalog()
                        .require(GPT_IMAGE_FUNCTION)
                        .adapter();
                CanvasFunctionFrozenRun run1 =
                    createTestRun(2L, oldAdapter.functions().get(0), "prompt before clear");
                oldAdapter.submit(execContext, run1);
                assertEquals(1, requests.size(), "request before CLEAR must succeed");
                assertEquals("Bearer " + key, requests.get(0).authorization());

                // 3. 执行 CLEAR 密钥
                commitService.configure(
                    PLUGIN_ID, 2L, 2L, values, Map.of("apiKey", PluginSecretPatch.clear()));
                runtime.refresh();

                // 数据库凭据行已被物理删除
                assertTrue(
                    credentialRepository.find(PLUGIN_ID).isEmpty(),
                    "credential row must be deleted upon CLEAR");
                PluginConfigurationRow historyRow =
                    configurationRepository.readConfiguration(PLUGIN_ID, 3L).orElseThrow();
                assertTrue(historyRow.configuredSecretFields().isEmpty());

                // 4. 验证 CLEAR 前保留的旧实例在 CLEAR 后被 fence 拦截，拒绝出网
                CanvasFunctionFrozenRun run2 =
                    createTestRun(
                        2L, oldAdapter.functions().get(0), "prompt with old lease after clear");
                assertThrows(
                    CanvasFunctionUnknownException.class,
                    () -> oldAdapter.submit(execContext, run2),
                    "old instance must fail-closed after secret CLEAR");
                assertEquals(
                    1,
                    requests.size(),
                    "old instance must not emit network request after secret CLEAR");

                // 5. 验证新 lease 获取的实例同样 fail-closed 且不出网
                try (PluginCatalogRuntime.Lease newLease = runtime.acquireCurrent()) {
                  CanvasFunctionAdapter newAdapter =
                      newLease
                          .snapshot()
                          .canvasFunctionCatalog()
                          .require(GPT_IMAGE_FUNCTION)
                          .adapter();
                  CanvasFunctionFrozenRun run3 =
                      createTestRun(
                          3L, newAdapter.functions().get(0), "prompt with new lease after clear");

                  assertThrows(
                      CanvasFunctionUnknownException.class,
                      () -> newAdapter.submit(execContext, run3));
                  assertEquals(
                      1,
                      requests.size(),
                      "new lease instance must not emit network request after CLEAR");
                }
              }
            });
  }

  @Test
  void restoreHistoricalEpochFromPostgresAndPreservesIsolation() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              // 推进到 epoch 2：设置 key-epoch-2，启用 paidEnabled
              String values2 =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrl
                      + "\",\"gptImage2\":{\"paidEnabled\":true}}";
              commitService.configure(
                  PLUGIN_ID,
                  1L,
                  1L,
                  values2,
                  Map.of("apiKey", PluginSecretPatch.set("key-epoch-2")));
              runtime.refresh();

              // 推进到 epoch 3：轮换 key 演进 authGeneration，并禁用 paidEnabled
              String values3 =
                  "{\"enabled\":true,\"baseUrl\":\""
                      + baseUrl
                      + "\",\"gptImage2\":{\"paidEnabled\":false}}";
              commitService.configure(
                  PLUGIN_ID,
                  2L,
                  2L,
                  values3,
                  Map.of("apiKey", PluginSecretPatch.set("key-epoch-3")));
              runtime.refresh();

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                assertEquals(3L, lease.snapshot().epoch());
              }
            });

    // 模拟全新启动的上下文（新节点），从同一个隔离数据库恢复
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime restartedRuntime = context.getBean(PluginCatalogRuntime.class);

              // 1. 当前快照是权威最新版本 (epoch 3)
              try (PluginCatalogRuntime.Lease currentLease = restartedRuntime.acquireCurrent()) {
                assertEquals(3L, currentLease.snapshot().epoch());
                assertFalse(
                    currentLease
                        .snapshot()
                        .canvasFunctionCatalog()
                        .require(GPT_IMAGE_FUNCTION)
                        .enabled());
              }

              // 2. 从 PostgreSQL 恢复历史 epoch 2
              try (PluginCatalogRuntime.Lease historicalLease = restartedRuntime.acquire(2L)) {
                PluginCatalogSnapshot historicalSnapshot = historicalLease.snapshot();
                assertEquals(2L, historicalSnapshot.epoch());
                CanvasFunctionCatalog.RegisteredFunction registered =
                    historicalSnapshot.canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION);
                assertTrue(registered.enabled(), "historical epoch 2 had paidEnabled=true");

                // 核心断言：历史 epoch 恢复的实例因持有旧代际凭据引用，调用前被 fence 拦截拒绝出网
                CanvasFunctionAdapter historicalAdapter = registered.adapter();
                CanvasFunctionExecutionContext execContext =
                    mock(CanvasFunctionExecutionContext.class);
                when(execContext.isRunning()).thenReturn(true);
                CanvasFunctionFrozenRun historicalRun =
                    createTestRun(2L, historicalAdapter.functions().get(0), "prompt historical");

                assertThrows(
                    CanvasFunctionUnknownException.class,
                    () -> historicalAdapter.submit(execContext, historicalRun),
                    "historical epoch instance with stale generation must be fenced before network egress");
                assertEquals(
                    0,
                    requests.size(),
                    "no HTTP request should reach server from fenced historical instance");
              }

              // 3. 租用结束后历史实例释放，当前快照保持最新
              try (PluginCatalogRuntime.Lease currentLease = restartedRuntime.acquireCurrent()) {
                assertEquals(3L, currentLease.snapshot().epoch());
              }
            });
  }

  private HttpServer createControlledServer(List<RecordedRequest> targetList) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/opencli/execute",
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          targetList.add(
              new RecordedRequest(
                  exchange.getRequestMethod(), exchange.getRequestURI().getPath(), auth, body));
          byte[] response =
              ("{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":{"
                      + "\"id\":\"exec-"
                      + UUID.randomUUID()
                      + "\","
                      + "\"status\":\"SUCCEEDED\","
                      + "\"stdoutTruncated\":false,"
                      + "\"stderrTruncated\":false,"
                      + "\"stdout\":\"ok\","
                      + "\"resources\":[]}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
          }
        });
    server.start();
    runningServers.add(server);
    return server;
  }

  private ApplicationContextRunner createContextRunner() {
    Class<?> openCliConfigClass;
    try {
      openCliConfigClass = Class.forName(OPENCLI_AUTO_CONFIGURATION_NAME);
    } catch (ClassNotFoundException error) {
      throw new AssertionError(
          "OpenCliAutoConfiguration must be available on the test runtime classpath", error);
    }

    return new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
        .withConfiguration(AutoConfigurations.of(openCliConfigClass))
        .withBean(PluginCredentialStore.class, () -> credentialStore)
        .withBean(PluginConfigurationRepository.class, () -> configurationRepository)
        .withBean(PlatformTransactionManager.class, () -> transactionManager);
  }

  private PluginConfigurationCommitService createCommitService(
      StudioPluginRegistry registry, PluginCatalogRuntime runtime) {
    return new PluginConfigurationCommitService(
        registry,
        configurationRepository,
        credentialRepository,
        codec,
        keyLoader,
        new PluginContextFactory(credentialStore, () -> null),
        List::of,
        List::of,
        transactionManager,
        Clock.systemUTC(),
        () -> {},
        runtime::refresh);
  }

  private static CanvasFunctionFrozenRun createTestRun(
      long epoch, CanvasFunctionDefinition definition, String prompt) {
    CanvasFunctionOutputSpec spec = definition.outputs().get(0);
    CanvasFunctionFrozenOutput output =
        new CanvasFunctionFrozenOutput(
            UUID.randomUUID(), 0, spec.kind(), spec.resolveName("output"));
    CanvasFunctionBinding binding = new CanvasFunctionBinding(epoch, definition, null);
    JsonObject args = new JsonObject(Map.of("prompt", new JsonText(prompt)));
    return new CanvasFunctionFrozenRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "output",
        UUID.randomUUID(),
        binding,
        args,
        List.of(),
        List.of(output),
        CanvasFunctionSubmitState.PENDING,
        "INITIAL",
        Map.of());
  }

  private static long readEpoch(JdbcClient jdbc) {
    return jdbc.sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private static int countConfigurationRevisions(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static boolean containsBytes(byte[] source, byte[] target) {
    if (source == null || target == null || target.length == 0 || source.length < target.length) {
      return false;
    }
    for (int i = 0; i <= source.length - target.length; i++) {
      boolean match = true;
      for (int j = 0; j < target.length; j++) {
        if (source[i + j] != target[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return true;
      }
    }
    return false;
  }

  private record RecordedRequest(String method, String path, String authorization, String body) {}

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {
    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
