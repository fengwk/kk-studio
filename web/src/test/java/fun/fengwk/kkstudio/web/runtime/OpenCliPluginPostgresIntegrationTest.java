package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.sun.net.httpserver.HttpServer;
import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
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
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
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
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.plugin.StudioPluginController;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
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
 * 变更或密钥撤销（CLEAR）后出网前 fail-closed； 6. 历史 epoch 恢复后旧代际实例在调用时 fail-closed； 7. 真实管理服务到 HTTP 的响应体与共用
 * golden 一致。
 */
class OpenCliPluginPostgresIntegrationTest {

  private static final String PLUGIN_ID = "opencli";
  private static final String GPT_IMAGE_FUNCTION = "gpt-image-2";
  private static final String OPENCLI_PACKAGE_PREFIX = "fun.fengwk.kkstudio.plugin.";
  private static final String OPENCLI_AUTO_CONFIGURATION_NAME =
      OPENCLI_PACKAGE_PREFIX + "opencli.OpenCliAutoConfiguration";
  private static final String HTTP_GOLDEN = "fun/fengwk/kkstudio/web/plugin/opencli-http.json";

  /** 合成凭据：只用于隔离数据库与 golden，本身没有价值。 */
  private static final String HTTP_GOLDEN_API_KEY = "synthetic-opencli-api-key-97531";

  private static final String HTTP_GOLDEN_INSTANCE_ID = "synthetic-instance-identity";

  /** 生产 HTTP mapper 与该 profile 自己的 MVC converter，golden 因此由真实序列化形态产出。 */
  private static final JsonMapper HTTP_MAPPER = StrictHttpJsonMapperTestSupport.get();

  private static final JacksonJsonHttpMessageConverter HTTP_CONVERTER =
      new JacksonJsonHttpMessageConverter(HTTP_MAPPER);

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
  void pinnedOldInstanceIsFencedBeforeEgressAfterBaseUrlChange() throws IOException {
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

  /**
   * 显式只读连接检查的真实 PG 集成：坐标门禁、真实只读 GET 与凭据撤销后的类型化投影。
   *
   * <p>权威 epoch 已前进但本节点尚未应用、以及 epoch/revision 与请求不一致都是 409 且零 HTTP；坐标命中后只发出一条只读 GET
   * /api/resources/dates 并携带当前 scoped 凭据；撤销认证密钥后本地凭据不可用，只投影为 NOT_CHECKED/UNAVAILABLE，
   * 绝不出网，也绝不把远端故障说成缺少凭据。
   */
  @Test
  void explicitConnectionCheckHonoursCoordinatesAndReadsOnlyResourceDates() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    String apiKey = "synthetic-opencli-check-key-13579";
    String valuesJson = "{\"enabled\":true,\"baseUrl\":\"" + baseUrl + "\"}";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createPendingCommitService(registry, runtime);
              PluginManagementService management = createManagementService(registry, runtime);

              commitService.configure(
                  PLUGIN_ID, 1L, 1L, valuesJson, Map.of("apiKey", PluginSecretPatch.set(apiKey)));

              // 权威 epoch 已到 2 但本节点尚未应用：pending 交付必须 409 且零 HTTP
              assertThrows(
                  AiVersionConflictException.class,
                  () -> management.checkConnection(PLUGIN_ID, 2L, 2L));
              assertEquals(0, requests.size(), "pending 配置不得触网");

              runtime.refresh();
              assertEquals(2L, readEpoch(jdbcClient));

              // 坐标不匹配（旧 epoch / 错误 revision）同样 409 零 HTTP
              assertThrows(
                  AiVersionConflictException.class,
                  () -> management.checkConnection(PLUGIN_ID, 2L, 1L));
              assertThrows(
                  AiVersionConflictException.class,
                  () -> management.checkConnection(PLUGIN_ID, 1L, 1L));
              assertEquals(0, requests.size(), "坐标不匹配不得触网");

              // 坐标完全命中：真实只读 GET，携带同一次 scoped 凭据
              PluginConnectionCheckDTO checked = management.checkConnection(PLUGIN_ID, 2L, 2L);
              assertEquals(PLUGIN_ID, checked.getPluginId());
              assertEquals("2", checked.getAppliedEpoch());
              assertEquals("2", checked.getAppliedRevision());
              assertEquals("REACHABLE", checked.getReachability());
              assertEquals("ACCEPTED", checked.getAuthentication());
              assertEquals("UNKNOWN", checked.getBrowserSession());
              assertEquals(1, requests.size(), "一次检查只发出一条请求");
              RecordedRequest probe = requests.get(0);
              assertEquals("GET", probe.method());
              assertEquals("/api/resources/dates", probe.path());
              assertEquals("Bearer " + apiKey, probe.authorization());
              assertTrue(
                  requests.stream().noneMatch(request -> request.path().contains("/execute")),
                  "连接检查绝不触发任何生成执行");

              // 撤销认证密钥：本地凭据不可用是唯一的 UNAVAILABLE 语义，绝不触网
              commitService.configure(
                  PLUGIN_ID, 2L, 2L, valuesJson, Map.of("apiKey", PluginSecretPatch.clear()));
              runtime.refresh();

              PluginConnectionCheckDTO revoked = management.checkConnection(PLUGIN_ID, 3L, 3L);
              assertEquals("NOT_CHECKED", revoked.getReachability());
              assertEquals("UNAVAILABLE", revoked.getAuthentication());
              assertEquals("UNKNOWN", revoked.getBrowserSession());
              assertEquals(1, requests.size(), "撤销认证密钥后不得触网");
            });
  }

  /**
   * 真实插件与真实管理服务到 HTTP 的共用 golden：GET /api/plugins/opencli 的响应体必须与已提交资源逐值一致。
   *
   * <p>配置只给出 baseUrl 与 seedance.workspaceId，其余取值由真实 schema 补齐，因此 golden 锁定的就是生产派生文本（默认值与
   * valuesSchema）； 前端直接消费同一份资源，不再手写镜像。
   */
  @Test
  void pluginProjectionMatchesCommittedHttpWireGolden() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);
              PluginManagementService management = createManagementService(registry, runtime);

              commitService.configure(
                  PLUGIN_ID,
                  1L,
                  1L,
                  "{\"baseUrl\":\"http://127.0.0.1:8080\","
                      + "\"seedance\":{\"workspaceId\":\"workspace-main\"}}",
                  Map.of(
                      "apiKey", PluginSecretPatch.set(HTTP_GOLDEN_API_KEY),
                      "instanceId", PluginSecretPatch.set(HTTP_GOLDEN_INSTANCE_ID)));
              runtime.refresh();
              assertEquals(2L, readEpoch(jdbcClient));

              assertSame(HTTP_MAPPER, HTTP_CONVERTER.getMapper());
              MockMvc mockMvc =
                  MockMvcBuilders.standaloneSetup(new StudioPluginController(management))
                      .setMessageConverters(HTTP_CONVERTER)
                      .setControllerAdvice(new ResultResponseBodyAdvice())
                      .build();

              MockHttpServletResponse response =
                  mockMvc.perform(get("/api/plugins/" + PLUGIN_ID)).andReturn().getResponse();

              assertEquals(200, response.getStatus());
              String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
              assertEquals(
                  wireView(goldenText(HTTP_GOLDEN)),
                  wireView(body),
                  HTTP_GOLDEN + " must match the live HTTP body");
            });
  }

  /**
   * 私有身份与认证密钥同为受保护秘密：只有 instanceId 不得显示 CONNECTED，取值不进入普通配置/历史/投影，并且执行请求只从同次 scoped snapshot 取用两者。
   *
   * <p>覆盖三段状态：只录入私有身份（NOT_CONNECTED 但仍以秘密落库）、补齐认证密钥（CONNECTED 且出网携带两者）、仅撤销认证密钥而保留私有身份 （回到
   * NOT_CONNECTED 且出网前 fail closed）。
   */
  @Test
  void privateIdentityIsAProtectedSecretAndNeverReportsConnectedOnItsOwn() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    String privateIdentity = "private-instance-identity";
    String apiKey = "synthetic-opencli-key-24680";
    String valuesJson =
        "{\"enabled\":true,\"baseUrl\":\"" + baseUrl + "\",\"gptImage2\":{\"paidEnabled\":true}}";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);
              PluginManagementService management = createManagementService(registry, runtime);

              // 1. 只录入私有身份：凭据行存在且可解密，但没有任何可用认证
              commitService.configure(
                  PLUGIN_ID,
                  1L,
                  1L,
                  valuesJson,
                  Map.of("instanceId", PluginSecretPatch.set(privateIdentity)));
              runtime.refresh();

              PluginCredentialRow row =
                  credentialRepository
                      .find(PLUGIN_ID)
                      .orElseThrow(() -> new AssertionError("private identity must be stored"));
              assertFalse(
                  containsBytes(
                      row.encryptedPayload(), privateIdentity.getBytes(StandardCharsets.UTF_8)),
                  "plaintext private identity must not exist in database");

              PluginDTO privateOnly = management.getPlugin(PLUGIN_ID);
              assertEquals(
                  PluginCredentialStatus.NOT_CONNECTED.name(),
                  privateOnly.getStatus(),
                  "只有私有身份时不得显示认证 CONNECTED");

              PluginConfigurationDTO configuration = privateOnly.getConfiguration();
              assertFalse(configuration.getValuesJson().contains(privateIdentity));
              assertTrue(configuration.getSecrets().get("instanceId").isConfigured());
              assertFalse(configuration.getSecrets().get("apiKey").isConfigured());

              PluginConfigurationRow privateHistory =
                  configurationRepository.readConfiguration(PLUGIN_ID, 2L).orElseThrow();
              assertEquals(List.of("instanceId"), privateHistory.configuredSecretFields());
              assertFalse(privateHistory.valuesJson().contains(privateIdentity));

              String resolved =
                  credentialStore
                      .resolve(
                          new PluginCredentialReference(
                              PLUGIN_ID, row.authGeneration(), row.contextFingerprint()))
                      .payloadJson();
              assertEquals("{\"instanceId\":\"" + privateIdentity + "\"}", resolved);

              // 2. 补齐认证密钥：状态变为 CONNECTED，出网同时携带同一 snapshot 的 Bearer 与私有身份
              commitService.configure(
                  PLUGIN_ID, 2L, 2L, valuesJson, Map.of("apiKey", PluginSecretPatch.set(apiKey)));
              runtime.refresh();

              assertEquals(
                  PluginCredentialStatus.CONNECTED.name(),
                  management.getPlugin(PLUGIN_ID).getStatus());

              CanvasFunctionExecutionContext execContext =
                  mock(CanvasFunctionExecutionContext.class);
              when(execContext.isRunning()).thenReturn(true);
              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                CanvasFunctionAdapter adapter =
                    lease.snapshot().canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION).adapter();
                adapter.submit(
                    execContext,
                    createTestRun(3L, adapter.functions().get(0), "generate portrait"));
              }

              assertEquals(1, requests.size());
              assertEquals("Bearer " + apiKey, requests.get(0).authorization());
              assertTrue(
                  requests.get(0).body().contains("\"instanceId\":\"" + privateIdentity + "\""),
                  "执行请求必须携带同次 snapshot 的私有身份");

              // 3. 只撤销认证密钥、保留私有身份：不得回到 CONNECTED，且新旧实例都 fail closed 不出网
              try (PluginCatalogRuntime.Lease staleLease = runtime.acquireCurrent()) {
                CanvasFunctionAdapter staleAdapter =
                    staleLease
                        .snapshot()
                        .canvasFunctionCatalog()
                        .require(GPT_IMAGE_FUNCTION)
                        .adapter();

                commitService.configure(
                    PLUGIN_ID, 3L, 3L, valuesJson, Map.of("apiKey", PluginSecretPatch.clear()));
                runtime.refresh();

                assertTrue(credentialRepository.find(PLUGIN_ID).isPresent(), "保留的私有身份仍是受保护秘密行");
                PluginConfigurationRow partialHistory =
                    configurationRepository.readConfiguration(PLUGIN_ID, 4L).orElseThrow();
                assertEquals(List.of("instanceId"), partialHistory.configuredSecretFields());
                assertEquals(
                    PluginCredentialStatus.NOT_CONNECTED.name(),
                    management.getPlugin(PLUGIN_ID).getStatus(),
                    "撤销认证密钥后只凭私有身份不得显示 CONNECTED");

                assertThrows(
                    CanvasFunctionUnknownException.class,
                    () ->
                        staleAdapter.submit(
                            execContext,
                            createTestRun(3L, staleAdapter.functions().get(0), "stale prompt")),
                    "撤销认证密钥后旧实例必须 fail closed");

                try (PluginCatalogRuntime.Lease freshLease = runtime.acquireCurrent()) {
                  CanvasFunctionAdapter freshAdapter =
                      freshLease
                          .snapshot()
                          .canvasFunctionCatalog()
                          .require(GPT_IMAGE_FUNCTION)
                          .adapter();
                  assertThrows(
                      CanvasFunctionUnknownException.class,
                      () ->
                          freshAdapter.submit(
                              execContext,
                              createTestRun(4L, freshAdapter.functions().get(0), "fresh prompt")),
                      "缺少认证密钥的载荷必须在出网前被拒绝");
                }
              }
              assertEquals(1, requests.size(), "撤销认证密钥后不得再有请求到达 Hub");
            });
  }

  /**
   * wire 值视图：配置里的内嵌 JSON 文本按其 JSON 值参与比较。
   *
   * <p>这些文本的 key 顺序来自生产 schema 的 map 迭代顺序，同一份配置在两次 JVM 之间可能不同，而顺序不是 wire 契约的一部分；除此之外（字段有无、 取值、token
   * 类型、内嵌文档的全部键值）都逐值比较。
   */
  private static JsonNode wireView(String body) {
    JsonNode root = JsonValues.readTree(body);
    JsonNode configuration = root.get("data").get("configuration");
    for (String field : List.of("valuesJson", "valuesSchema", "defaultsJson")) {
      JsonNode text = configuration.get(field);
      if (text != null && text.isTextual()) {
        ((ObjectNode) configuration).set(field, JsonValues.readTree(text.asString()));
      }
    }
    return root;
  }

  /** 已提交的共用 golden 原文：测试只读取，绝不在运行时改写资源。 */
  private static String goldenText(String name) {
    ClassPathResource resource = new ClassPathResource(name);
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("shared plugin HTTP golden must exist: " + name, error);
    }
  }

  private HttpServer createControlledServer(List<RecordedRequest> targetList) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 显式连接检查使用的只读端点：只记录请求并返回合法信封，绝不产生任何生成副作用。
    server.createContext(
        "/api/resources/dates",
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          targetList.add(
              new RecordedRequest(
                  exchange.getRequestMethod(), exchange.getRequestURI().getPath(), auth, body));
          byte[] response =
              ("{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":[\"2026-10-16\"]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
          }
        });
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
        // PluginCatalogRuntime 显式依赖 systemProxySelector：隔离组合根提供直接连接的合法 selector 依赖，
        // 不安装 JVM 全局代理，保持与其他测试的全局代理隔离。
        .withBean(
            "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
        .withBean(PluginCredentialStore.class, () -> credentialStore)
        .withBean(PluginConfigurationRepository.class, () -> configurationRepository)
        .withBean(PlatformTransactionManager.class, () -> transactionManager);
  }

  private PluginManagementService createManagementService(
      StudioPluginRegistry registry, PluginCatalogRuntime runtime) {
    return new PluginManagementService(
        registry,
        () -> runtime,
        configurationRepository,
        credentialStore,
        createCommitService(registry, runtime),
        mock(PluginAuthAttemptRepository.class),
        Duration.ofMinutes(5),
        Clock.systemUTC());
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

  /** 提交后只请求对账、不立即应用的提交服务：生产里提交只唤醒本节点对账，因此在 refresh 之前真实存在「已提交但未应用」的 pending 窗口。 */
  private PluginConfigurationCommitService createPendingCommitService(
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
        () -> {});
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
