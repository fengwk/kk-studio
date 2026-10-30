package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.settings.HttpProxySelector;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.plugin.StudioPluginController;

import javax.sql.DataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ComfyUI 插件在真实隔离 PostgreSQL 上的 runtime-only 契约与热更新验收。
 *
 * <p>插件只经运行期依赖与反射装配（本类不 import 任何插件实现类）：默认不启用即零贡献；配置切换经唯一写入口落库并在本节点应用，已取得的租用继续用旧实例完成；
 * 插件拒绝的配置不写入；停用后零贡献。本类使用独立隔离库，绝不修改共享 web 库。
 */
@ResourceLock("jvm-proxy-selector")
class ComfyuiPluginPostgresIntegrationTest {

  private static final String COMFYUI = "comfyui";

  /** 与前端共用的真实 schema/default HTTP golden：由生产 mapper 产出，测试只比对，绝不在运行期改写资源。 */
  private static final String HTTP_GOLDEN = "fun/fengwk/kkstudio/web/plugin/comfyui-http.json";

  private static final String FUNCTION = "direct-history";
  private static final String ARGUMENT = "text";

  /** 运行期 JAR 上的插件前缀：与其它 runtime-only 测试一致，只拼前缀而不写完整类名。 */
  private static final String PLUGIN_PREFIX = "fun.fengwk.kkstudio.plugin.";

  /** 生产 HTTP mapper 与该 profile 自己的 MVC converter，golden 因此由真实序列化形态产出。 */
  private static final JsonMapper HTTP_MAPPER = StrictHttpJsonMapperTestSupport.get();

  private static final JacksonJsonHttpMessageConverter HTTP_CONVERTER =
      new JacksonJsonHttpMessageConverter(HTTP_MAPPER);

  private static final String AUTO_CONFIGURATION =
      PLUGIN_PREFIX + "comfyui.ComfyuiAutoConfiguration";

  /** 两个可区分的图：注入目标与输出 selector 节点都不同，因此新旧配置的解释必然不同。 */
  private static final String GRAPH_OLD =
      "{\"1\":{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"\"}},"
          + "\"2\":{\"class_type\":\"SaveVideo\",\"inputs\":{\"filename_prefix\":\"old\",\"video\":[\"9\",0]}}}";

  private static final String GRAPH_NEW =
      "{\"3\":{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"\"}},"
          + "\"4\":{\"class_type\":\"SaveVideo\",\"inputs\":{\"filename_prefix\":\"new\",\"video\":[\"9\",0]}}}";

  private static final String ARGS_SCHEMA =
      "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\""
          + ARGUMENT
          + "\":{\"type\":\"string\"}},\"required\":[\""
          + ARGUMENT
          + "\"]}";

  private DataSource dataSource;
  private PluginConfigurationRepository repository;
  private HttpServer server;
  private final Map<String, String> saveNodeByPrompt = new LinkedHashMap<>();

  @BeforeEach
  void createIsolatedDatabaseAndStub() throws IOException {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    repository = new PluginConfigurationRepository(JdbcClient.create(dataSource));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/prompt", this::handlePrompt);
    server.createContext("/history/", this::handleHistory);
    server.createContext("/view", this::handleView);
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  /** 未启用即零贡献：真实启动只写入默认配置，不发布任何 ComfyUI 函数。 */
  @Test
  void disabledByDefaultContributesNothing() {
    runner()
        .run(
            context -> {
              try (PluginCatalogRuntime.Lease lease = runtime(context).acquire()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(
                    List.of(COMFYUI), context.getBean(StudioPluginRegistry.class).pluginIds());
                assertTrue(snapshot.configuredPlugins().get(0).functionNames().isEmpty());
                assertThrows(
                    IllegalArgumentException.class,
                    () -> snapshot.canvasFunctionCatalog().require(FUNCTION));
              }
              assertEquals(1L, repository.find(COMFYUI).orElseThrow().revision());
            });
  }

  /** 配置切换后新租用解释新图；切换前取得的租用继续用旧实例完成，重启后按持久配置恢复新图。 */
  @Test
  void graphChangeAppliesToNewLeasesAndSurvivesRestart() {
    runner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = runtime(context);
              PluginConfigurationService configuration = configurationService(context, runtime);
              configuration.configure(
                  COMFYUI, 1L, enabledValues(GRAPH_OLD, "1", "value", "2"), Map.of());

              try (PluginCatalogRuntime.Lease old = runtime.acquire()) {
                configuration.configure(
                    COMFYUI, 2L, enabledValues(GRAPH_NEW, "3", "value", "4"), Map.of());
                try (PluginCatalogRuntime.Lease current = runtime.acquire()) {
                  assertEquals(4, executeOnce(current));
                }
                assertEquals(2, executeOnce(old), "a leased instance keeps its configuration");
              }
            });
    runner()
        .run(
            restarted -> {
              try (PluginCatalogRuntime.Lease lease = runtime(restarted).acquire()) {
                assertEquals(4, executeOnce(lease));
              }
            });
  }

  /** 插件拒绝的配置在写入前失败，修订与当前目录都不变。 */
  @Test
  void invalidConfigurationIsRejectedBeforeWriting() {
    runner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = runtime(context);
              PluginConfigurationService configuration = configurationService(context, runtime);

              assertThrows(
                  AiValidationException.class,
                  () ->
                      configuration.configure(
                          COMFYUI, 1L, enabledValues("not-json", "1", "value", "2"), Map.of()));

              assertEquals(1L, repository.find(COMFYUI).orElseThrow().revision());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                assertTrue(lease.snapshot().canvasFunctionCatalog().list().isEmpty());
              }
            });
  }

  /** 停用后零贡献。 */
  @Test
  void disablingRemovesEveryComfyuiContribution() {
    runner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = runtime(context);
              PluginConfigurationService configuration = configurationService(context, runtime);
              configuration.configure(
                  COMFYUI, 1L, enabledValues(GRAPH_OLD, "1", "value", "2"), Map.of());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                assertEquals(2, executeOnce(lease));
              }

              configuration.configure(COMFYUI, 2L, disabledValues(), Map.of());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                assertTrue(lease.snapshot().configuredPlugins().get(0).functionNames().isEmpty());
                assertTrue(lease.snapshot().canvasFunctionCatalog().list().isEmpty());
              }
            });
  }

  /**
   * 真实 ComfyUI schema/default 到 HTTP 的共用 golden：GET /api/plugins/comfyui 的响应体必须与已提交资源逐值一致。
   *
   * <p>配置来自启动期写入的 schema 默认值，因此 golden 锁定的就是生产派生文本；前端消费同一份资源。
   */
  @Test
  void pluginProjectionMatchesCommittedHttpWireGolden() {
    runner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = runtime(context);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginManagementService management =
                  new PluginManagementService(
                      registry,
                      () -> runtime,
                      repository,
                      new PluginCredentialRepository(JdbcClient.create(dataSource)),
                      configurationService(context, runtime),
                      mock(PluginAuthAttemptRepository.class),
                      Duration.ofMinutes(5),
                      Clock.systemUTC());

              assertSame(HTTP_MAPPER, HTTP_CONVERTER.getMapper());
              MockMvc mockMvc =
                  MockMvcBuilders.standaloneSetup(new StudioPluginController(management))
                      .setMessageConverters(HTTP_CONVERTER)
                      .setControllerAdvice(new ResultResponseBodyAdvice())
                      .build();
              MockHttpServletResponse response =
                  mockMvc.perform(get("/api/plugins/" + COMFYUI)).andReturn().getResponse();

              assertEquals(200, response.getStatus());
              String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
              assertEquals(
                  wireView(goldenText(HTTP_GOLDEN)),
                  wireView(body),
                  HTTP_GOLDEN + " must match the live HTTP body");
            });
  }

  /**
   * wire 值视图：配置里的内嵌 JSON 文本按其 JSON 值参与比较。
   *
   * <p>内嵌文本的对象键顺序来自生产 schema 的 map 迭代顺序，同一份配置在两次 JVM 之间可能不同，而顺序不是 wire 契约的一部分； 除此之外（字段有无、取值、token
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

  // ---------------------------------------------------------------- 运行期装配

  /** 真实启动链路：只加载运行期 JAR 上的 ComfyUI 自动配置，绝不 import 插件实现。 */
  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
        .withConfiguration(AutoConfigurations.of(autoConfiguration()))
        .withBean(
            PluginContextFactory.class,
            () ->
                new PluginContextFactory(
                    mock(PluginCredentialStore.class), () -> null, () -> null, () -> null))
        .withBean(
            "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
        .withBean(PluginConfigurationRepository.class, () -> repository);
  }

  private static PluginCatalogRuntime runtime(ApplicationContext context) {
    return context.getBean(PluginCatalogRuntime.class);
  }

  /** 与生产一致的写入口；提交后立即在本节点应用。 */
  private PluginConfigurationService configurationService(
      ApplicationContext context, PluginCatalogRuntime runtime) {
    StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
    return new PluginConfigurationService(
        registry,
        repository,
        new PluginCredentialRepository(JdbcClient.create(dataSource)),
        context.getBean(PluginContextFactory.class),
        new PluginAgentDependencies(registry, repository, mock(AgentDefinitionRepository.class)),
        new DataSourceTransactionManager(dataSource),
        runtime::refresh,
        () -> {});
  }

  private static Class<?> autoConfiguration() {
    try {
      return Class.forName(AUTO_CONFIGURATION);
    } catch (ClassNotFoundException error) {
      throw new AssertionError(
          "the ComfyUI plugin must be on the web test runtime classpath", error);
    }
  }

  private StudioPluginRegistry registry(ConfigurableApplicationContext context) {
    return context.getBean(StudioPluginRegistry.class);
  }

  /**
   * 通过真实 runtime 目录取 adapter，跑一次真实 submit + history + materialize，返回物化字节的唯一值。
   *
   * <p>桩 ComfyUI 按提交图里唯一的 SaveVideo 节点回输出，因此返回值就等于该配置定义侧 selector 的目标节点 id。
   */
  private int executeOnce(PluginCatalogRuntime.Lease lease) {
    CanvasFunctionDefinition definition =
        lease.snapshot().canvasFunctionCatalog().require(FUNCTION).function();
    CanvasFunctionAdapter adapter =
        lease.snapshot().canvasFunctionCatalog().require(FUNCTION).adapter();

    Map<String, CanvasJson> args = new LinkedHashMap<>();
    args.put(ARGUMENT, new JsonText("injected"));
    RecordingContext context = new RecordingContext();
    CanvasFunctionFrozenRun fresh =
        new CanvasFunctionFrozenRun(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "comfyui-node",
            UUID.randomUUID(),
            new CanvasFunctionBinding(definition),
            new JsonObject(args),
            List.of(),
            List.of(
                new CanvasFunctionFrozenOutput(
                    UUID.randomUUID(), 0, CanvasResourceKind.VIDEO, "video")),
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.submit(context, fresh);
    adapter.execute(
        context,
        new CanvasFunctionFrozenRun(
            fresh.canvasId(),
            fresh.nodeId(),
            fresh.nodeName(),
            fresh.requestId(),
            fresh.binding(),
            fresh.args(),
            fresh.manifest(),
            fresh.outputs(),
            CanvasFunctionSubmitState.SUBMITTED,
            "H3_COMFY_WAITING",
            context.lastAdapterState));
    assertEquals(1, context.materialized.size());
    return context.materialized.get(0);
  }

  // ---------------------------------------------------------------- 配置提交

  private String serverUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 停用修订：以插件自己的 schema 默认值为基线，只改 baseUrl 与启用位。 */
  private String disabledValues() {
    ObjectNode defaults = (ObjectNode) JsonValues.readTree(schemaDefaults());
    defaults.put("baseUrl", serverUrl());
    defaults.put("enabled", false);
    return JsonValues.write(defaults);
  }

  /** 启用一个真实可执行的 DIRECT 工作流：参数绑定、0 引用策略与单一 selector 都由配置声明。 */
  private String enabledValues(String graph, String nodeId, String input, String selectorNode) {
    ObjectNode values = (ObjectNode) JsonValues.readTree(schemaDefaults());
    values.put("enabled", true);
    values.put("baseUrl", serverUrl());
    ArrayNode workflows = values.putArray("workflows");
    ObjectNode workflow = workflows.addObject();
    workflow.put("id", FUNCTION);
    workflow.put("description", "direct history workflow");
    workflow.put("strategy", "DIRECT");
    workflow.put("workflowJson", graph);
    workflow.put("argsSchemaJson", ARGS_SCHEMA);
    ObjectNode binding = workflow.putArray("parameterBindings").addObject();
    binding.put("argument", ARGUMENT);
    binding.put("nodeId", nodeId);
    binding.put("input", input);
    workflow.putArray("mediaBindings");
    ObjectNode output = workflow.putArray("outputs").addObject();
    output.put("kind", "VIDEO");
    output.put("nodeId", selectorNode);
    output.put("field", "videos");
    output.put("index", 0);
    ObjectNode policy = workflow.putObject("referencePolicy");
    policy.putArray("allowedKinds").add("IMAGE");
    policy.put("maxCount", 0);
    ObjectNode byKind = policy.putArray("maxCountByKind").addObject();
    byKind.put("kind", "IMAGE");
    byKind.put("maxCount", 0);
    return JsonValues.write(values);
  }

  /** 插件 schema 默认值经运行期反射取得：web 不 compile import 插件实现。 */
  private String schemaDefaults() {
    try {
      Class<?> pluginClass = Class.forName(PLUGIN_PREFIX + "comfyui.ComfyuiPlugin");
      StudioPlugin plugin = (StudioPlugin) pluginClass.getDeclaredConstructor().newInstance();
      assertTrue(plugin.descriptor().pluginId().equals(COMFYUI));
      return plugin.configurationSchema().defaultsJson();
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(
          "the ComfyUI plugin must be on the web test runtime classpath", error);
    }
  }

  // ---------------------------------------------------------------- 桩 ComfyUI

  private void handlePrompt(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String promptId = "p" + saveNodeByPrompt.size();
    saveNodeByPrompt.put(promptId, saveNode(body));
    respond(exchange, "{\"prompt_id\":\"" + promptId + "\",\"node_errors\":{}}");
  }

  private void handleHistory(HttpExchange exchange) throws IOException {
    String promptId = exchange.getRequestURI().getPath().substring("/history/".length());
    String saveNode = saveNodeByPrompt.get(promptId);
    if (saveNode == null) {
      respond(exchange, "{}");
      return;
    }
    respond(
        exchange,
        "{\""
            + promptId
            + "\":{\"status\":{\"status_str\":\"success\",\"completed\":true},\"outputs\":{\""
            + saveNode
            + "\":{\"videos\":[{\"filename\":\"node-"
            + saveNode
            + ".mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
  }

  private void handleView(HttpExchange exchange) throws IOException {
    String filename = "";
    for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
      if (pair.startsWith("filename=")) {
        filename = pair.substring("filename=".length());
      }
    }
    // 桩文件名形如 node-<nodeId>.mp4：显式去掉前缀与后缀，绝不靠"删掉非数字"（.mp4 自带数字）。
    String nodeId = filename.substring("node-".length(), filename.length() - ".mp4".length());
    byte[] content = new byte[] {(byte) Integer.parseInt(nodeId)};
    exchange.getResponseHeaders().add("Content-Type", "video/mp4");
    exchange.sendResponseHeaders(200, content.length);
    try (var out = exchange.getResponseBody()) {
      out.write(content);
    }
  }

  /** 提交图里唯一的 SaveVideo 节点：桩输出因此总能匹配定义侧 selector 的目标节点。 */
  private static String saveNode(String body) {
    JsonNode prompt = JsonValues.readTree(body).get("prompt");
    for (String nodeId : prompt.propertyNames()) {
      if ("SaveVideo".equals(prompt.get(nodeId).path("class_type").asText())) {
        return nodeId;
      }
    }
    throw new IllegalArgumentException("submitted graph declares no SaveVideo node");
  }

  private void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 冻结 run 的宿主上下文：只记录 checkpoint 与物化结果。 */
  private static final class RecordingContext implements CanvasFunctionExecutionContext {

    private Map<String, Object> lastAdapterState = Map.of();
    private final List<Integer> materialized = new ArrayList<>();

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      this.lastAdapterState = adapterState;
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      return new CanvasFunctionResourceStream(new ByteArrayInputStream(new byte[0]), 0L, () -> {});
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      return "https://example.com/presigned/" + reference.resourceId();
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      try {
        materialized.add((int) content.readAllBytes()[0]);
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
      return output.resourceId();
    }

    @Override
    public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
      throw new UnsupportedOperationException("ComfyUI outputs are media slots");
    }
  }

  /** 测试自有注册表：只登记运行期 classpath 上真实存在的 StudioPlugin。 */
  @Configuration
  static class RegistryConfiguration {

    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
