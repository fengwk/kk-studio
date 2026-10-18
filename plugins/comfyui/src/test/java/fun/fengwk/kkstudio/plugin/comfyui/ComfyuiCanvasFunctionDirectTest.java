package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DIRECT 工作流的真实 HTTP 端到端：绑定注入、提交-轮询-下载、断点恢复与认证。
 *
 * <p>用真实 {@link StandardComfyuiClient} 打本地 ComfyUI 协议 route，验证 DIRECT 不只是"存了一份没用到的 JSON"，且断点恢复绝不重提。
 */
@ResourceLock("jvm-proxy-selector")
class ComfyuiCanvasFunctionDirectTest {

  private static final UUID CANVAS = new UUID(0L, 7L);
  private static final UUID NODE = new UUID(0L, 8L);
  private static final UUID REQUEST = new UUID(0L, 9L);
  private static final UUID FIRST_TARGET = new UUID(0L, 91L);
  private static final UUID SECOND_TARGET = new UUID(0L, 92L);
  private static final UUID SOURCE_NODE = new UUID(0L, 2L);
  private static final UUID RESOURCE = new UUID(0L, 11L);

  private static final String PROMPT_ID = "p1";

  private static final String GRAPH =
      """
      {
        "1": {"class_type": "LoadImage", "inputs": {"image": ""}},
        "2": {"class_type": "PrimitiveString", "inputs": {"value": ""}},
        "3": {"class_type": "PrimitiveInt", "inputs": {"value": 0}},
        "4": {"class_type": "SaveVideo", "inputs": {"filename_prefix": "out", "video": ["5", 0]}}
      }
      """;

  private static final String ARGS_SCHEMA =
      """
      {
        "type": "object",
        "additionalProperties": false,
        "properties": {"prompt": {"type": "string"}, "duration": {"type": "integer"}},
        "required": ["prompt"]
      }
      """;

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final String DIRECT_PARAMETER_BINDINGS =
      "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"},"
          + "{\"argument\":\"duration\",\"nodeId\":\"3\",\"input\":\"value\"}]";

  private static final String DIRECT_MEDIA_BINDINGS =
      "[{\"referenceIndex\":0,\"nodeId\":\"1\",\"input\":\"image\"}]";

  private static final String DIRECT_OUTPUTS =
      "[{\"name\":\"preview\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":1},"
          + "{\"name\":\"final\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]";

  private static final String WITH_FLAVOUR_BINDINGS =
      "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"},"
          + "{\"argument\":\"duration\",\"nodeId\":\"3\",\"input\":\"value\"},"
          + "{\"argument\":\"flavour\",\"nodeId\":\"1000\",\"input\":\"value\"}]";

  private static final String WITH_STYLE_BINDINGS =
      "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"},"
          + "{\"argument\":\"duration\",\"nodeId\":\"3\",\"input\":\"value\"},"
          + "{\"argument\":\"style\",\"nodeId\":\"1000\",\"input\":\"value\"}]";

  /** 与 {@link #GRAPH} 相同，另加一个 1000 号静态节点：DIRECT 不受 H3 保留动态区约束。 */
  private static final String GRAPH_WITH_1000 =
      """
      {
        "1": {"class_type": "LoadImage", "inputs": {"image": ""}},
        "2": {"class_type": "PrimitiveString", "inputs": {"value": ""}},
        "3": {"class_type": "PrimitiveInt", "inputs": {"value": 0}},
        "4": {"class_type": "SaveVideo", "inputs": {"filename_prefix": "out", "video": ["5", 0]}},
        "1000": {"class_type": "PrimitiveString", "inputs": {"value": "synthetic"}}
      }
      """;

  private HttpServer server;
  private String baseUrl;

  private final AtomicInteger uploads = new AtomicInteger();
  private final AtomicInteger promptPosts = new AtomicInteger();
  private final AtomicInteger historyCalls = new AtomicInteger();
  private final AtomicInteger viewCalls = new AtomicInteger();
  private final AtomicReference<String> uploadAuthorization = new AtomicReference<>();
  private final AtomicReference<String> promptAuthorization = new AtomicReference<>();
  private final AtomicReference<String> historyAuthorization = new AtomicReference<>();
  private final AtomicReference<String> viewAuthorization = new AtomicReference<>();
  private final AtomicReference<ObjectNode> submittedPrompt = new AtomicReference<>();
  private final AtomicReference<String> uploadedPath = new AtomicReference<>();
  private final AtomicReference<String> historyJson = new AtomicReference<>(historySuccess());
  private volatile int protectedStatus = 200;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/upload/image", exchange -> handleUpload(exchange));
    server.createContext("/prompt", exchange -> handlePrompt(exchange));
    server.createContext("/history/", exchange -> handleHistory(exchange));
    server.createContext("/view", exchange -> handleView(exchange));
    server.createContext("/queue", exchange -> handleQueue(exchange));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void executesDirectWorkflowOverRealHttpWithBoundArgumentsAndMedia() throws IOException {
    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.NONE, null);
    ComfyuiCanvasFunctionAdapter adapter = directAdapter(client);
    RecordingContext submitContext = new RecordingContext();
    CanvasFunctionFrozenRun fresh =
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            H3AdapterState.empty().encode());

    adapter.submit(submitContext, fresh);

    assertEquals(1, uploads.get());
    assertEquals(1, promptPosts.get());
    ObjectNode prompt = submittedPrompt.get();
    assertEquals("kk-studio-" + NODE + "-" + FIRST_TARGET, prompt.path("client_id").asText());
    JsonNode graph = prompt.path("prompt");
    // 已声明参数按绑定注入到既有 node/input。
    assertEquals("animate it", graph.path("2").path("inputs").path("value").asText());
    assertEquals(5, graph.path("3").path("inputs").path("value").asInt());
    // 冻结媒体按 manifest 顺序写入，且上传路径来自真实 /upload/image 响应。
    assertEquals(
        "kk-studio/" + CANVAS + "/" + uploadedPath.get(),
        graph.path("1").path("inputs").path("image").asText());
    // 未被绑定的输入保持冻结图原值：请求无法改写图结构。
    assertEquals("out", graph.path("4").path("inputs").path("filename_prefix").asText());
    assertEquals(4, graph.size());
    // 上传前登记 COMFY_UPLOADING，每个上传产物产生后再次登记，然后才进入提交边界。
    assertEquals(
        List.of(
            ComfyuiCanvasFunctionAdapter.COMFY_UPLOADING,
            ComfyuiCanvasFunctionAdapter.COMFY_UPLOADING,
            ComfyuiCanvasFunctionAdapter.COMFY_SUBMITTING,
            ComfyuiCanvasFunctionAdapter.COMFY_WAITING),
        submitContext.stages);
    H3AdapterState submittedState = H3AdapterState.decode(submitContext.lastAdapterState);
    assertEquals(PROMPT_ID, submittedState.promptId());
    assertEquals(1, submittedState.uploads().size());

    // 恢复：只有拿到 promptId 才查询，然后按冻结 selector 顺序下载并物化。
    RecordingContext resumeContext = new RecordingContext();
    CanvasFunctionFrozenRun resumable =
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
            submitContext.lastAdapterState);
    List<UUID> materialized = adapter.execute(resumeContext, resumable);

    assertEquals(List.of(FIRST_TARGET, SECOND_TARGET), materialized);
    assertEquals(1, historyCalls.get());
    assertEquals(2, viewCalls.get());
    assertArrayEquals(new byte[] {7, 7}, resumeContext.materializedBytes.get(FIRST_TARGET));
    assertArrayEquals(new byte[] {9}, resumeContext.materializedBytes.get(SECOND_TARGET));
    assertEquals(
        List.of(ComfyuiCanvasFunctionAdapter.COMFY_READY, ComfyuiCanvasFunctionAdapter.COMPLETE),
        resumeContext.stages);
  }

  /** 已声明但缺省且无 schema default 的参数必须在任何上传/Prompt/远端提交之前失败：PREFLIGHT 只做纯值解析。 */
  @Test
  void knownArgumentErrorsFailBeforeAnyUploadOrSubmit() {
    String argsSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {"prompt": {"type": "string"}, "duration": {"type": "integer"},
            "flavour": {"type": "string"}},
          "required": ["prompt"]
        }
        """;
    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.NONE, null);
    ComfyuiCanvasFunctionAdapter adapter =
        directAdapter(client, GRAPH_WITH_1000, argsSchema, WITH_FLAVOUR_BINDINGS);
    RecordingContext context = new RecordingContext();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.submit(
                    context,
                    run(
                        adapter,
                        CanvasFunctionSubmitState.SUBMITTED,
                        "QUEUED",
                        H3AdapterState.empty().encode())));

    assertEquals("workflow argument is missing: flavour", failure.getMessage());
    // 纯校验：没有上传、没有提交、也没有任何 checkpoint。
    assertEquals(0, uploads.get());
    assertEquals(0, promptPosts.get());
    assertEquals(0, historyCalls.get());
    assertEquals(List.of(), context.stages);
  }

  /** DIRECT 可用保留区节点 id，且参数按"显式值优先、其次 schema default"注入，EXPLICIT 与 default 行为都不变。 */
  @Test
  void directInjectsExplicitValuesAndSchemaDefaultsIncludingReservedNodeIds() {
    String argsSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {"prompt": {"type": "string"}, "duration": {"type": "integer"},
            "style": {"type": "string", "default": "cinematic"}},
          "required": ["prompt"]
        }
        """;
    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.NONE, null);
    ComfyuiCanvasFunctionAdapter adapter =
        directAdapter(client, GRAPH_WITH_1000, argsSchema, WITH_STYLE_BINDINGS);

    adapter.submit(
        new RecordingContext(),
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            H3AdapterState.empty().encode()));

    assertEquals(1, uploads.get());
    assertEquals(1, promptPosts.get());
    JsonNode graph = submittedPrompt.get().path("prompt");
    assertEquals("animate it", graph.path("2").path("inputs").path("value").asText());
    assertEquals(5, graph.path("3").path("inputs").path("value").asInt());
    assertEquals("cinematic", graph.path("1000").path("inputs").path("value").asText());
    assertEquals(5, graph.size());
  }

  /** 冻结图只以副本暴露：改写 accessor 拿到的副本不影响后续构图，连续两次运行得到同一冻结解释。 */
  @Test
  void mutatingGraphAccessorCopiesNeverPollutesLaterRuns() {
    ComfyuiSettings settings = directSettings(GRAPH, ARGS_SCHEMA, DIRECT_PARAMETER_BINDINGS);
    ComfyuiWorkflow workflow = settings.workflows().get(0);
    ObjectNode leaked = workflow.graph();
    ((ObjectNode) leaked.get("2").get("inputs")).put("value", "leaked");
    leaked.set(
        "1000",
        (ObjectNode)
            JsonValues.readTree(
                "{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"leaked\"}}"));
    assertEquals(4, workflow.graph().size());
    assertEquals(2, workflow.definition().outputs().size());

    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.NONE, null);
    ComfyuiCanvasFunctionAdapter adapter = directAdapter(settings, client);
    for (int attempt = 0; attempt < 2; attempt++) {
      adapter.submit(
          new RecordingContext(),
          run(
              adapter,
              CanvasFunctionSubmitState.SUBMITTED,
              "QUEUED",
              H3AdapterState.empty().encode()));
      JsonNode graph = submittedPrompt.get().path("prompt");
      assertEquals("animate it", graph.path("2").path("inputs").path("value").asText());
      assertEquals(4, graph.size());
      assertFalse(graph.has("1000"));
      assertEquals(
          "kk-studio/" + CANVAS + "/" + uploadedPath.get(),
          graph.path("1").path("inputs").path("image").asText());
    }
    // 两次运行都各自重新上传与提交，解释始终来自同一份冻结快照。
    assertEquals(2, uploads.get());
    assertEquals(2, promptPosts.get());
  }

  /** 提交边界之后的恢复只能查询：无 promptId 的 SUBMITTING 一律 UNKNOWN，绝不自动重提。 */
  @Test
  void neverResubmitsAfterTheSubmissionBoundary() {
    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.NONE, null);
    ComfyuiCanvasFunctionAdapter adapter = directAdapter(client);

    CanvasFunctionFrozenRun unknown =
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_SUBMITTING,
            H3AdapterState.empty().encode());
    assertThrows(
        CanvasFunctionUnknownException.class,
        () -> adapter.submit(new RecordingContext(), unknown));
    assertEquals(0, promptPosts.get());
    assertEquals(0, uploads.get());

    // 已有 handle 的 SUBMITTING 恢复只查询，不重提。
    Map<String, Object> submitted = H3AdapterState.empty().withPromptId(PROMPT_ID).encode();
    CanvasFunctionFrozenRun withHandle =
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_SUBMITTING,
            submitted);
    adapter.submit(new RecordingContext(), withHandle);
    assertEquals(0, promptPosts.get());

    RecordingContext context = new RecordingContext();
    adapter.execute(
        context,
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
            submitted));
    assertEquals(0, promptPosts.get());
    assertEquals(1, historyCalls.get());

    // 已保存 outputs 的恢复不再查询远端，只重放下载。
    int before = historyCalls.get();
    RecordingContext readyContext = new RecordingContext();
    adapter.execute(
        readyContext,
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_READY,
            H3AdapterState.decode(submitted)
                .withOutputs(List.of(new H3OutputDescriptor("final.mp4", "video", "output")))
                .encode()));
    assertEquals(before, historyCalls.get());
    assertEquals(1, readyContext.materializedBytes.size());

    // 没有 promptId 时查询无法成立：确定性失败，不猜测也不重提。
    assertThrows(
        CanvasFunctionUnknownException.class,
        () ->
            adapter.execute(
                new RecordingContext(),
                run(
                    adapter,
                    CanvasFunctionSubmitState.SUBMITTED,
                    ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
                    H3AdapterState.empty().encode())));
  }

  /** BEARER 模式每个受保护请求独立解析 scoped 快照；401 只作废本次快照，绝不降级为无认证。 */
  @Test
  void bearerAuthIsResolvedPerRequestAndRevokedOnUnauthorized() {
    PluginCredentialAccess access = mock(PluginCredentialAccess.class);
    PluginCredentialSnapshot snapshot = snapshot("test-token");
    when(access.resolve()).thenReturn(snapshot);
    StandardComfyuiClient client = client(ComfyuiSettings.AuthMode.BEARER, access);
    ComfyuiCanvasFunctionAdapter adapter = directAdapter(client);

    RecordingContext submitContext = new RecordingContext();
    adapter.submit(
        submitContext,
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            H3AdapterState.empty().encode()));
    adapter.execute(
        new RecordingContext(),
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
            submitContext.lastAdapterState));

    assertEquals("Bearer test-token", uploadAuthorization.get());
    assertEquals("Bearer test-token", promptAuthorization.get());
    assertEquals("Bearer test-token", historyAuthorization.get());
    assertEquals("Bearer test-token", viewAuthorization.get());
    verify(access, atLeast(4)).resolve();

    protectedStatus = 401;
    IllegalStateException unauthorized =
        assertThrows(IllegalStateException.class, () -> client.history(PROMPT_ID));
    assertEquals("ComfyUI request failed with HTTP 401", unauthorized.getMessage());
    verify(access).rejectUsed(snapshot, "ComfyUI rejected the bearer credential");

    // 撤销后凭据不可用：确定性失败，且不再发出任何请求。
    when(access.resolve())
        .thenThrow(
            new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.REAUTH_REQUIRED, "reauth required"));
    int before = historyCalls.get();
    assertThrows(PluginCredentialUnavailableException.class, () -> client.history(PROMPT_ID));
    assertEquals(before, historyCalls.get());
    // 撤销后的失败不产生任何出网请求，之前记录的头未被覆盖。
    assertEquals("Bearer test-token", historyAuthorization.get());
  }

  /** H3 selector 不被锁死为 92：构图 prefix 与输出解释都跟随冻结定义所选节点。 */
  @Test
  void h3SelectorNodeDrivesCompositionAndOutputInterpretation() throws IOException {
    ComfyuiSettings settings =
        ComfyuiSettings.parse(
            ComfyuiWorkflowFixtures.settingsJson(
                true,
                ComfyuiWorkflowFixtures.h3Array(
                    ComfyuiWorkflowFixtures.h3WithSaveVideoNode("900"))));
    ComfyuiWorkflow h3 = settings.workflows().get(0);
    assertEquals("900", h3.outputs().get(0).nodeId());

    StandardComfyuiClient client = mock(StandardComfyuiClient.class);
    when(client.submit(any(), any())).thenReturn(PROMPT_ID);
    when(client.upload(any(), any(), any(Long.class), any(), any()))
        .thenReturn(new H3UploadedFile("11.png", "kk-studio/" + CANVAS, "input"));
    PluginPromptTasks promptTasks = mock(PluginPromptTasks.class);
    PluginPromptResources promptResources = mock(PluginPromptResources.class);
    ComfyuiCanvasFunctionAdapter adapter =
        new ComfyuiCanvasFunctionAdapter(
            settings,
            h3,
            client,
            new H3MediaPreflight(),
            new H3PromptRequestBuilder(),
            promptTasks,
            promptResources);

    RecordingContext context = new RecordingContext();
    CanvasFunctionFrozenRun frozen =
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            H3AdapterState.empty().withSeed(7L).withEnhancedPrompt("enhanced").encode());
    adapter.submit(context, frozen);

    ObjectNode submitted = capturedSubmit(client);
    // 构图使用所选定义：只有被 selector 选中的 SaveVideo 节点被写入本次运行的 prefix。
    assertEquals(
        "video/kk-studio-" + FIRST_TARGET,
        submitted.path("900").path("inputs").path("filename_prefix").asText());
    assertFalse(
        ("video/kk-studio-" + FIRST_TARGET)
            .equals(submitted.path("92").path("inputs").path("filename_prefix").asText()));
    // 未声明的节点不是输出兜底：历史里只有 92 时解释失败。
    when(client.history(PROMPT_ID))
        .thenReturn(
            protocolHistory(
                "{\"92\":{\"videos\":[{\"filename\":\"final.mp4\",\"subfolder\":\"video\","
                    + "\"type\":\"output\"}]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.execute(
                new RecordingContext(),
                run(
                    adapter,
                    CanvasFunctionSubmitState.SUBMITTED,
                    ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
                    context.lastAdapterState)));

    when(client.history(PROMPT_ID))
        .thenReturn(
            protocolHistory(
                "{\"900\":{\"videos\":[{\"filename\":\"chosen.mp4\",\"subfolder\":\"video\","
                    + "\"type\":\"output\"}]}}"));
    when(client.download(any()))
        .thenReturn(new H3ComfyDownload(new ByteArrayInputStream(new byte[] {1}), 1L, "video/mp4"));
    RecordingContext executeContext = new RecordingContext();
    adapter.execute(
        executeContext,
        run(
            adapter,
            CanvasFunctionSubmitState.SUBMITTED,
            ComfyuiCanvasFunctionAdapter.COMFY_WAITING,
            context.lastAdapterState));
    assertArrayEquals(new byte[] {1}, executeContext.materializedBytes.get(FIRST_TARGET));
  }

  // ---------------------------------------------------------------- HTTP routes

  private void handleUpload(HttpExchange exchange) throws IOException {
    uploads.incrementAndGet();
    uploadAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String filename = multipartValue(body, "filename=\"", "\"");
    String subfolder = multipartValue(body, "name=\"subfolder\"\r\n\r\n", "\r\n");
    uploadedPath.set(filename);
    respond(
        exchange,
        200,
        "application/json",
        "{\"name\":\"" + filename + "\",\"subfolder\":\"" + subfolder + "\",\"type\":\"input\"}");
  }

  private void handlePrompt(HttpExchange exchange) throws IOException {
    promptPosts.incrementAndGet();
    promptAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    submittedPrompt.set(
        (ObjectNode)
            JsonValues.readTree(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    if (rejectProtected(exchange)) {
      return;
    }
    respond(
        exchange,
        200,
        "application/json",
        "{\"prompt_id\":\"" + PROMPT_ID + "\",\"node_errors\":{}}");
  }

  private void handleHistory(HttpExchange exchange) throws IOException {
    historyCalls.incrementAndGet();
    historyAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    if (rejectProtected(exchange)) {
      return;
    }
    respond(exchange, 200, "application/json", historyJson.get());
  }

  private void handleView(HttpExchange exchange) throws IOException {
    viewCalls.incrementAndGet();
    viewAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    byte[] content = "final.mp4".equals(query.get("filename")) ? new byte[] {9} : new byte[] {7, 7};
    exchange.getResponseHeaders().add("Content-Type", "video/mp4");
    exchange.sendResponseHeaders(200, content.length);
    try (var out = exchange.getResponseBody()) {
      out.write(content);
    }
  }

  private void handleQueue(HttpExchange exchange) throws IOException {
    respond(exchange, 200, "application/json", "{\"queue_running\":[],\"queue_pending\":[]}");
  }

  private boolean rejectProtected(HttpExchange exchange) throws IOException {
    if (protectedStatus == 200) {
      return false;
    }
    respond(exchange, protectedStatus, "application/json", "{}");
    return true;
  }

  // ---------------------------------------------------------------- helpers

  private StandardComfyuiClient client(
      ComfyuiSettings.AuthMode authMode, PluginCredentialAccess access) {
    return new StandardComfyuiClient(
        baseUrl, authMode, Duration.ofSeconds(2), Duration.ofSeconds(5), MAPPER, access);
  }

  /** DIRECT 工作流不需要 Prompt 端口：构造参数必须允许为空。 */
  private ComfyuiCanvasFunctionAdapter directAdapter(StandardComfyuiClient client) {
    return directAdapter(directSettings(GRAPH, ARGS_SCHEMA, DIRECT_PARAMETER_BINDINGS), client);
  }

  private ComfyuiCanvasFunctionAdapter directAdapter(
      StandardComfyuiClient client, String graph, String argsSchema, String parameterBindings) {
    return directAdapter(directSettings(graph, argsSchema, parameterBindings), client);
  }

  private static ComfyuiCanvasFunctionAdapter directAdapter(
      ComfyuiSettings settings, StandardComfyuiClient client) {
    return new ComfyuiCanvasFunctionAdapter(
        settings, settings.workflows().get(0), client, null, null, null, null);
  }

  private static ComfyuiSettings directSettings(
      String graph, String argsSchema, String parameterBindings) {
    return ComfyuiSettings.parse(
        ComfyuiWorkflowFixtures.settingsJson(
            true,
            ComfyuiWorkflowFixtures.singleWorkflowArray(
                ComfyuiWorkflowFixtures.workflow(
                    "direct-flow",
                    "DIRECT",
                    graph,
                    argsSchema,
                    parameterBindings,
                    DIRECT_MEDIA_BINDINGS,
                    DIRECT_OUTPUTS,
                    ComfyuiWorkflowFixtures.IMAGE_POLICY))));
  }

  private static ObjectNode capturedSubmit(StandardComfyuiClient client) {
    ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
    verify(client, atLeast(1)).submit(captor.capture(), any());
    return captor.getValue();
  }

  private CanvasFunctionFrozenRun run(
      ComfyuiCanvasFunctionAdapter adapter,
      CanvasFunctionSubmitState submitState,
      String stage,
      Map<String, Object> adapterState) {
    Map<String, CanvasJson> args = new LinkedHashMap<>();
    args.put("prompt", new JsonText("animate it"));
    args.put("duration", new JsonNumber(BigDecimal.valueOf(5)));
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "direct",
        REQUEST,
        new CanvasFunctionBinding(adapter.functions().get(0)),
        new JsonObject(args),
        List.of(imageReference()),
        outputs(adapter),
        submitState,
        stage,
        adapterState);
  }

  private static List<CanvasFunctionFrozenOutput> outputs(ComfyuiCanvasFunctionAdapter adapter) {
    List<CanvasFunctionOutputSpec> specs = adapter.functions().get(0).outputs();
    List<CanvasFunctionFrozenOutput> outputs = new ArrayList<>(specs.size());
    List<String> names = List.of("preview", "final");
    List<UUID> targets = List.of(FIRST_TARGET, SECOND_TARGET);
    for (int index = 0; index < specs.size(); index++) {
      outputs.add(
          new CanvasFunctionFrozenOutput(
              targets.get(index), index, specs.get(index).kind(), names.get(index)));
    }
    return outputs;
  }

  private static PluginCredentialSnapshot snapshot(String apiKeyLiteral) {
    return new PluginCredentialSnapshot(
        "comfyui", null, null, 1L, "{\"apiKey\":\"" + apiKeyLiteral + "\"}");
  }

  private static CanvasFunctionFrozenReference imageReference() {
    return new CanvasFunctionFrozenReference(
        SOURCE_NODE,
        0,
        RESOURCE,
        RESOURCE,
        CanvasResourceKind.IMAGE,
        "source.png",
        "image/png",
        3L,
        512L,
        512L,
        null);
  }

  private static String historySuccess() {
    return historySuccessWith(
        "{\"4\":{\"videos\":["
            + "{\"filename\":\"final.mp4\",\"subfolder\":\"video\",\"type\":\"output\"},"
            + "{\"filename\":\"preview.mp4\",\"subfolder\":\"video\",\"type\":\"output\"}]}}");
  }

  private static String historySuccessWith(String outputs) {
    return "{\""
        + PROMPT_ID
        + "\":{\"status\":{\"status_str\":\"success\",\"completed\":true},\"outputs\":"
        + outputs
        + "}}";
  }

  private static H3ComfyHistory protocolHistory(String outputs) {
    return H3ComfyHistory.success((ObjectNode) JsonValues.readTree(outputs));
  }

  private void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> query(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawQuery == null) {
      return values;
    }
    for (String pair : rawQuery.split("&")) {
      int separator = pair.indexOf('=');
      if (separator > 0) {
        values.put(
            URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
      }
    }
    return values;
  }

  /** 从 multipart 正文里取一个字段值；缺失即视为测试装置错误。 */
  private static String multipartValue(String text, String prefix, String suffix) {
    int start = text.indexOf(prefix);
    assertTrue(start >= 0, "missing multipart field " + prefix);
    start += prefix.length();
    int end = text.indexOf(suffix, start);
    assertTrue(end >= 0, "missing multipart terminator for " + prefix);
    return text.substring(start, end);
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private Map<String, Object> lastAdapterState = Map.of();
    private final Map<UUID, byte[]> materializedBytes = new LinkedHashMap<>();

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
      this.lastAdapterState = adapterState;
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      return new CanvasFunctionResourceStream(
          new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, () -> {});
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      return "https://example.com/presigned/" + reference.resourceId();
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      try {
        materializedBytes.put(output.resourceId(), content.readAllBytes());
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
}
