package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginSecretPatch;
import fun.fengwk.kkstudio.platform.settings.HttpProxySelector;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真实隔离 PostgreSQL 与真实 HTTP 上 OpenCLI 插件的凭据与生命周期集成测试。
 *
 * <p>验证：启动只写入默认配置；保存的密钥以 Bearer 出网；轮换密钥后已租用的旧实例也立即使用新密钥（凭据每次调用现读）；目标地址变化而未重新录入密钥、或 撤销密钥后，新旧实例都在出网前
 * fail closed；连接检查只对本节点已应用的修订执行；真实管理服务到 HTTP 的响应体与共用 golden 一致；私有身份是受保护秘密，单独存在时不显示 CONNECTED。
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

  private DataSource dataSource;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private DatabasePluginCredentialStore credentialStore;
  private PlatformTransactionManager transactionManager;
  private final CanvasFunctionExecutionContext execContext =
      mock(CanvasFunctionExecutionContext.class);

  private final List<HttpServer> runningServers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    JdbcClient jdbcClient = JdbcClient.create(dataSource);
    configurationRepository = new PluginConfigurationRepository(jdbcClient);
    credentialRepository = new PluginCredentialRepository(jdbcClient);
    credentialStore = new DatabasePluginCredentialStore(credentialRepository, Clock.systemUTC());
    transactionManager = new DataSourceTransactionManager(dataSource);
    when(execContext.isRunning()).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    for (HttpServer server : runningServers) {
      server.stop(0);
    }
    runningServers.clear();
  }

  /** 首次启动写入默认配置（未启用），全部函数不可用且不创建凭据行。 */
  @Test
  void startupSeedsDisabledDefaults() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              assertEquals("OpenCLI", registry.find(PLUGIN_ID).orElseThrow().descriptor().name());

              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                CanvasFunctionCatalog catalog = lease.snapshot().canvasFunctionCatalog();
                assertEquals(
                    5, catalog.list().size(), "opencli must contribute 5 canvas functions");
                for (CanvasFunctionCatalog.RegisteredFunction fn : catalog.list()) {
                  assertFalse(fn.enabled(), "functions must be disabled by default");
                  assertNotNull(fn.unavailableReason());
                }
              }
              assertEquals(1L, configurationRepository.find(PLUGIN_ID).orElseThrow().revision());
              assertTrue(credentialRepository.find(PLUGIN_ID).isEmpty());
            });
  }

  /** 保存的密钥以 Bearer 随真实 HTTP 请求发出。 */
  @Test
  void savedKeyIsSentAsBearerOverRealHttp() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    String values = enabledValues(createControlledServer(requests));

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginConfigurationService configuration = configurationService(context, runtime);

              configuration.configure(
                  PLUGIN_ID, 1L, values, Map.of("apiKey", PluginSecretPatch.set("key-1")));

              assertEquals(
                  PluginCredentialStatus.CONNECTED,
                  credentialRepository.find(PLUGIN_ID).orElseThrow().status());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                CanvasFunctionCatalog.RegisteredFunction registered =
                    lease.snapshot().canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION);
                assertTrue(registered.enabled());
                submit(registered.adapter(), "generate portrait");
              }
              assertEquals(1, requests.size());
              assertEquals("POST", requests.get(0).method());
              assertEquals("/api/opencli/execute", requests.get(0).path());
              assertEquals("Bearer key-1", requests.get(0).authorization());
            });
  }

  /** 轮换密钥后，已租用的旧实例与新实例都立即使用新密钥。 */
  @Test
  void rotatedKeyIsUsedImmediatelyByLeasedInstances() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    String values = enabledValues(createControlledServer(requests));

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginConfigurationService configuration = configurationService(context, runtime);
              configuration.configure(
                  PLUGIN_ID, 1L, values, Map.of("apiKey", PluginSecretPatch.set("key-1")));

              try (PluginCatalogRuntime.Lease oldLease = runtime.acquire()) {
                CanvasFunctionAdapter oldAdapter = adapter(oldLease);
                configuration.configure(
                    PLUGIN_ID, 2L, values, Map.of("apiKey", PluginSecretPatch.set("key-2")));

                submit(oldAdapter, "leased instance");
                try (PluginCatalogRuntime.Lease newLease = runtime.acquire()) {
                  submit(adapter(newLease), "fresh instance");
                }
              }
              assertEquals(
                  List.of("Bearer key-2", "Bearer key-2"),
                  requests.stream().map(RecordedRequest::authorization).toList());
            });
  }

  /** 目标地址变化而没有重新录入密钥时，旧密钥被删除，旧实例在出网前 fail closed。 */
  @Test
  void endpointChangeWithoutNewKeyDropsTheKeyBeforeEgress() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    HttpServer server = createControlledServer(requests);
    String values = enabledValues(server);
    String moved =
        "{\"enabled\":true,\"baseUrl\":\"http://localhost:"
            + server.getAddress().getPort()
            + "\",\"gptImage2\":{\"paidEnabled\":true}}";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginConfigurationService configuration = configurationService(context, runtime);
              configuration.configure(
                  PLUGIN_ID, 1L, values, Map.of("apiKey", PluginSecretPatch.set("key-1")));

              try (PluginCatalogRuntime.Lease oldLease = runtime.acquire()) {
                CanvasFunctionAdapter oldAdapter = adapter(oldLease);
                configuration.configure(PLUGIN_ID, 2L, moved, Map.of());

                assertTrue(credentialRepository.find(PLUGIN_ID).isEmpty());
                assertThrows(
                    CanvasFunctionUnknownException.class, () -> submit(oldAdapter, "old target"));
              }
              assertEquals(0, requests.size(), "the old key must never reach any target");
            });
  }

  /** 撤销密钥后新旧实例都在出网前 fail closed。 */
  @Test
  void clearedKeyFailsClosedBeforeEgress() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    String values = enabledValues(createControlledServer(requests));

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginConfigurationService configuration = configurationService(context, runtime);
              configuration.configure(
                  PLUGIN_ID, 1L, values, Map.of("apiKey", PluginSecretPatch.set("key-1")));

              try (PluginCatalogRuntime.Lease oldLease = runtime.acquire()) {
                CanvasFunctionAdapter oldAdapter = adapter(oldLease);
                submit(oldAdapter, "before clear");
                configuration.configure(
                    PLUGIN_ID, 2L, values, Map.of("apiKey", PluginSecretPatch.clear()));

                assertTrue(credentialRepository.find(PLUGIN_ID).isEmpty());
                assertThrows(
                    CanvasFunctionUnknownException.class, () -> submit(oldAdapter, "after clear"));
                try (PluginCatalogRuntime.Lease newLease = runtime.acquire()) {
                  CanvasFunctionAdapter newAdapter = adapter(newLease);
                  assertThrows(
                      CanvasFunctionUnknownException.class, () -> submit(newAdapter, "fresh"));
                }
              }
              assertEquals(1, requests.size(), "only the request before CLEAR reaches the Hub");
            });
  }

  /**
   * 显式连接检查只对本节点已应用的修订执行。
   *
   * <p>已保存但本节点尚未应用、以及修订与请求不一致都是 409 且零 HTTP；修订命中后只发出一条只读 GET /api/resources/dates 并携带当前凭据；撤销密钥后只投影为
   * NOT_CHECKED/UNAVAILABLE，绝不出网。
   */
  @Test
  void explicitConnectionCheckHonoursTheAppliedRevision() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    String values =
        "{\"enabled\":true,\"baseUrl\":\"http://127.0.0.1:"
            + createControlledServer(requests).getAddress().getPort()
            + "\"}";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              // 只保存、不立即应用：refresh 之前真实存在「已保存但未应用」的窗口。
              PluginConfigurationService pending =
                  configurationService(registry, context, () -> {});
              PluginManagementService management = managementService(registry, runtime, pending);

              pending.configure(
                  PLUGIN_ID, 1L, values, Map.of("apiKey", PluginSecretPatch.set("check-key")));
              assertThrows(
                  AiVersionConflictException.class,
                  () -> management.checkConnection(PLUGIN_ID, 2L));

              runtime.refresh();
              assertThrows(
                  AiVersionConflictException.class,
                  () -> management.checkConnection(PLUGIN_ID, 1L));
              assertEquals(
                  0, requests.size(), "unapplied or mismatched revisions must not reach out");

              PluginConnectionCheckDTO checked = management.checkConnection(PLUGIN_ID, 2L);
              assertEquals("2", checked.getAppliedRevision());
              assertEquals(
                  PluginConnectionCheckDTO.Reachability.REACHABLE, checked.getReachability());
              assertEquals(
                  PluginConnectionCheckDTO.Authentication.ACCEPTED, checked.getAuthentication());
              assertEquals(1, requests.size());
              assertEquals("GET", requests.get(0).method());
              assertEquals("/api/resources/dates", requests.get(0).path());
              assertEquals("Bearer check-key", requests.get(0).authorization());

              pending.configure(PLUGIN_ID, 2L, values, Map.of("apiKey", PluginSecretPatch.clear()));
              runtime.refresh();
              PluginConnectionCheckDTO revoked = management.checkConnection(PLUGIN_ID, 3L);
              assertEquals(
                  PluginConnectionCheckDTO.Reachability.NOT_CHECKED, revoked.getReachability());
              assertEquals(
                  PluginConnectionCheckDTO.Authentication.UNAVAILABLE, revoked.getAuthentication());
              assertEquals(1, requests.size(), "a revoked key must not reach out");
            });
  }

  /**
   * 真实插件与真实管理服务到 HTTP 的共用 golden：GET /api/plugins/opencli 的响应体必须与已提交资源逐值一致。
   *
   * <p>配置只给出 baseUrl 与 seedance.workspaceId，其余取值由真实 schema 补齐，因此 golden 锁定的就是生产派生文本；前端直接消费同一份资源。
   */
  @Test
  void pluginProjectionMatchesCommittedHttpWireGolden() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationService configuration = configurationService(context, runtime);
              PluginManagementService management =
                  managementService(registry, runtime, configuration);

              configuration.configure(
                  PLUGIN_ID,
                  1L,
                  "{\"baseUrl\":\"http://127.0.0.1:8080\","
                      + "\"seedance\":{\"workspaceId\":\"workspace-main\"}}",
                  Map.of(
                      "apiKey", PluginSecretPatch.set(HTTP_GOLDEN_API_KEY),
                      "instanceId", PluginSecretPatch.set(HTTP_GOLDEN_INSTANCE_ID)));

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
              assertFalse(body.contains(HTTP_GOLDEN_API_KEY));
              assertEquals(
                  wireView(goldenText(HTTP_GOLDEN)),
                  wireView(body),
                  HTTP_GOLDEN + " must match the live HTTP body");
            });
  }

  /**
   * 私有身份与认证密钥同为受保护秘密：只有 instanceId 时不得显示 CONNECTED，取值不进入普通配置与投影；执行请求从同一次凭据读取两者；只撤销认证密钥后回到
   * NOT_CONNECTED 且出网前 fail closed。
   */
  @Test
  void privateIdentityIsAProtectedSecretAndNeverReportsConnectedOnItsOwn() throws IOException {
    List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    String values = enabledValues(createControlledServer(requests));
    String privateIdentity = "private-instance-identity";

    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationService configuration = configurationService(context, runtime);
              PluginManagementService management =
                  managementService(registry, runtime, configuration);

              configuration.configure(
                  PLUGIN_ID,
                  1L,
                  values,
                  Map.of("instanceId", PluginSecretPatch.set(privateIdentity)));
              PluginDTO privateOnly = management.getPlugin(PLUGIN_ID);
              assertEquals(PluginCredentialStatus.NOT_CONNECTED.name(), privateOnly.getStatus());
              PluginConfigurationDTO projected = privateOnly.getConfiguration();
              assertFalse(projected.getValuesJson().contains(privateIdentity));
              assertTrue(projected.getSecrets().get("instanceId").isConfigured());
              assertFalse(projected.getSecrets().get("apiKey").isConfigured());

              configuration.configure(
                  PLUGIN_ID, 2L, values, Map.of("apiKey", PluginSecretPatch.set("key-1")));
              assertEquals(
                  PluginCredentialStatus.CONNECTED.name(),
                  management.getPlugin(PLUGIN_ID).getStatus());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                submit(adapter(lease), "generate portrait");
              }
              assertEquals(1, requests.size());
              assertEquals("Bearer key-1", requests.get(0).authorization());
              assertTrue(
                  requests.get(0).body().contains("\"instanceId\":\"" + privateIdentity + "\""));

              configuration.configure(
                  PLUGIN_ID, 3L, values, Map.of("apiKey", PluginSecretPatch.clear()));
              assertTrue(credentialRepository.find(PLUGIN_ID).isPresent());
              assertEquals(
                  PluginCredentialStatus.NOT_CONNECTED.name(),
                  management.getPlugin(PLUGIN_ID).getStatus());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                CanvasFunctionAdapter adapter = adapter(lease);
                assertThrows(
                    CanvasFunctionUnknownException.class, () -> submit(adapter, "no api key"));
              }
              assertEquals(1, requests.size(), "no request may reach the Hub without an api key");
            });
  }

  private static String enabledValues(HttpServer server) {
    return "{\"enabled\":true,\"baseUrl\":\"http://127.0.0.1:"
        + server.getAddress().getPort()
        + "\",\"gptImage2\":{\"paidEnabled\":true}}";
  }

  private static CanvasFunctionAdapter adapter(PluginCatalogRuntime.Lease lease) {
    return lease.snapshot().canvasFunctionCatalog().require(GPT_IMAGE_FUNCTION).adapter();
  }

  private void submit(CanvasFunctionAdapter adapter, String prompt) {
    adapter.submit(execContext, createTestRun(adapter.functions().get(0), prompt));
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
        // 隔离组合根提供直连的 selector 依赖，不安装 JVM 全局代理。
        .withBean(
            "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
        .withBean(
            PluginContextFactory.class,
            () -> new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null))
        .withBean(PluginConfigurationRepository.class, () -> configurationRepository);
  }

  /** 与生产一致的写入口；提交后立即在本节点应用，使断言不依赖后台对账。 */
  private PluginConfigurationService configurationService(
      ApplicationContext context, PluginCatalogRuntime runtime) {
    return configurationService(
        context.getBean(StudioPluginRegistry.class), context, runtime::refresh);
  }

  private PluginConfigurationService configurationService(
      StudioPluginRegistry registry, ApplicationContext context, Runnable catalogChanged) {
    return new PluginConfigurationService(
        registry,
        configurationRepository,
        credentialRepository,
        context.getBean(PluginContextFactory.class),
        new PluginAgentDependencies(
            registry, configurationRepository, mock(AgentDefinitionRepository.class)),
        transactionManager,
        catalogChanged,
        () -> {});
  }

  private PluginManagementService managementService(
      StudioPluginRegistry registry,
      PluginCatalogRuntime runtime,
      PluginConfigurationService configuration) {
    return new PluginManagementService(
        registry,
        () -> runtime,
        configurationRepository,
        credentialRepository,
        configuration,
        mock(PluginAuthAttemptRepository.class),
        Duration.ofMinutes(5),
        Clock.systemUTC());
  }

  private static CanvasFunctionFrozenRun createTestRun(
      CanvasFunctionDefinition definition, String prompt) {
    CanvasFunctionOutputSpec spec = definition.outputs().get(0);
    CanvasFunctionFrozenOutput output =
        new CanvasFunctionFrozenOutput(
            UUID.randomUUID(), 0, spec.kind(), spec.resolveName("output"));
    JsonObject args = new JsonObject(Map.of("prompt", new JsonText(prompt)));
    return new CanvasFunctionFrozenRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "output",
        UUID.randomUUID(),
        new CanvasFunctionBinding(definition),
        args,
        List.of(),
        List.of(output),
        CanvasFunctionSubmitState.PENDING,
        "INITIAL",
        Map.of());
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
