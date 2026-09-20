package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisClient;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisHttpRequest;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisHttpResponse;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisHttpTransport;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisRegion;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCapabilityCache;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCredentialPayload;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisHarnessContributor;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisPlugin;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisResourceAccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiniMax Mavis 全部 15 项能力工具的真实执行与传输对齐测试。
 *
 * <p>用假 HTTP 传输记录请求并驱动真实 MavisClient，断言发往网关 MCP endpoint 的完整请求路径、 POST 方法、Bearer 授权头与 JSON
 * 参数体；验证目录请求先发生且在缓存 TTL 内不重复发送； 验证纯文本能力返回 JsonResultContent，媒体能力经网关暂存返回 ResourceResultContent 且
 * detailsJson 不泄露第三方 URL； 验证构造与初始化阶段不发生任何网络调用（未联网启动）。
 */
class MiniMaxMavisToolExecutionTest {

  private static final String TEST_TOKEN = "mavis-execution-test-token-12345";
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";
  private static final String STAGED_BLOB_ID = "22222222-3333-4444-5555-666666666666";
  private static final String MOCK_MEDIA_URL =
      "https://cdn.minimax.chat/generated/media-output.bin";
  private static final String CATALOG_RESPONSE =
      "{\"tools\":["
          + "{\"endpoint\":\"web_search\"},"
          + "{\"endpoint\":\"extract_content_from_websites\"},"
          + "{\"endpoint\":\"images_search_and_download\"},"
          + "{\"endpoint\":\"image_reverse_search\"},"
          + "{\"endpoint\":\"images_understand\"},"
          + "{\"endpoint\":\"audios_understand\"},"
          + "{\"endpoint\":\"videos_understand\"},"
          + "{\"endpoint\":\"listen_audio\"},"
          + "{\"endpoint\":\"get_voice_list\"},"
          + "{\"endpoint\":\"synthesize_speech\"},"
          + "{\"endpoint\":\"batch_text_to_audio\"},"
          + "{\"endpoint\":\"image_synthesize\"},"
          + "{\"endpoint\":\"batch_text_to_music\"},"
          + "{\"endpoint\":\"submit_video_generation\"},"
          + "{\"endpoint\":\"query_video_generation\"}"
          + "]}";

  private FakeMavisTransport transport;
  private FakeResourceGateway gateway;
  private DirectExecutor executor;
  private PluginCredentialStore credentialStore;
  private MiniMaxMavisHarnessContributor contributor;
  private Map<MavisCapability, Tool> tools;

  @BeforeEach
  void setUp() {
    this.transport = new FakeMavisTransport();
    this.gateway = new FakeResourceGateway();
    this.executor = new DirectExecutor();

    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            MiniMaxMavisPlugin.PLUGIN_ID,
            MavisRegion.CN.id().toUpperCase(),
            Instant.now().plusSeconds(3600),
            new MiniMaxMavisCredentialPayload(TEST_TOKEN, CLIENT_UUID, Instant.now()).toJson());
    this.credentialStore = new FakeCredentialStore(snapshot);

    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache capabilityCache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess resourceAccess = new MiniMaxMavisResourceAccess(gateway);

    MiniMaxMavisHarnessContributor.ToolDependencies dependencies =
        new MiniMaxMavisHarnessContributor.ToolDependencies(
            credentialStore, client, capabilityCache, resourceAccess, executor);
    this.contributor = MiniMaxMavisHarnessContributor.create(dependencies);
    this.tools = contributor.tools();
  }

  /** 构造工具与 contributor 的全过程不得触发任何 transport 调用（未联网启动）。 */
  @Test
  void initializationDoesNotTriggerAnyTransportRequests() {
    FakeMavisTransport freshTransport = new FakeMavisTransport();
    MavisClient client = new MavisClient(freshTransport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    MiniMaxMavisHarnessContributor freshContributor =
        MiniMaxMavisHarnessContributor.create(
            new MiniMaxMavisHarnessContributor.ToolDependencies(
                credentialStore, client, cache, access, executor));

    assertEquals(15, freshContributor.toolCount());
    assertTrue(
        freshTransport.requests().isEmpty(),
        "Plugin and tool initialization must not make any network transport calls");
  }

  /**
   * 参数化测试全部 15 个能力的执行： 验证 MCP endpoint 请求路径、POST 方法、Bearer token、body 匹配； 验证纯文本结果为
   * JsonResultContent，媒体能力结果为 ResourceResultContent 且 detailsJson 不含 http。
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(MavisCapability.class)
  void executesEveryCapabilitySuccessfully(MavisCapability capability) {
    Tool tool = tools.get(capability);
    String arguments = sampleArguments(capability);
    ToolCall call = new ToolCall("call-" + capability.id(), capability.toolName(), arguments);
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, capability.requestTimeout());

    TestToolExecutionListener listener = new TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertTrue(result != null, "Tool execution must invoke the completion listener");
    assertFalse(result.error(), () -> "Tool execution failed: " + result);
    assertEquals("call-" + capability.id(), result.toolCallId());

    // 校验发出的 HTTP 请求
    List<MavisHttpRequest> sentRequests = transport.requests();
    assertTrue(
        sentRequests.size() >= 2, "Must send at least catalog request and MCP invoke request");

    // 目录请求先发生
    MavisHttpRequest catalogRequest = sentRequests.get(0);
    assertEquals("GET", catalogRequest.method());
    assertEquals("https://agent.minimaxi.com/mavis/api/v1/mcp/tools", catalogRequest.url());
    assertEquals("Bearer " + TEST_TOKEN, catalogRequest.headers().get("Authorization"));

    // 能力调用请求
    MavisHttpRequest invokeRequest = sentRequests.get(sentRequests.size() - 1);
    assertEquals("POST", invokeRequest.method());
    assertEquals(
        "https://agent.minimaxi.com/mavis/api/v1/mcp/" + capability.endpoint(),
        invokeRequest.url(),
        "MCP request URL must match region baseUrl + /mavis/api/v1/mcp/<endpoint>");
    assertEquals("Bearer " + TEST_TOKEN, invokeRequest.headers().get("Authorization"));
    assertEquals(
        arguments, invokeRequest.body(), "MCP request body must match input arguments JSON");

    // 校验结果内容形态
    if (MiniMaxMavisTool.stagesMedia(capability)) {
      assertFalse(result.contents().isEmpty(), "Media capability must produce contents");
      for (var content : result.contents()) {
        assertInstanceOf(
            ResourceResultContent.class,
            content,
            "Media capability content must be ResourceResultContent");
        ResourceResultContent mediaContent = (ResourceResultContent) content;
        assertEquals(
            "blob-upload:" + STAGED_BLOB_ID,
            mediaContent.resource().uri(),
            "ResourceRef URI must originate from gateway staging");
      }
      assertFalse(
          result.detailsJson().contains("http"),
          () ->
              "Media capability detailsJson must not contain third-party HTTP/HTTPS URL: "
                  + result.detailsJson());
    } else {
      assertEquals(
          1, result.contents().size(), "Text capability must return exactly one result content");
      assertInstanceOf(
          JsonResultContent.class,
          result.contents().get(0),
          "Text capability content must be JsonResultContent");
      JsonResultContent textContent = (JsonResultContent) result.contents().get(0);
      assertTrue(
          textContent.json().contains("\"status_code\":0"),
          "Text content must equal provider response JSON");
    }
  }

  /** 验证 capability catalog 目录请求先发生且只发生一次，有缓存时后续调用不再重复发送 catalog 请求。 */
  @Test
  void capabilityCatalogIsRequestedOnlyOnceWithinTtl() {
    Tool tool = tools.get(MavisCapability.WEB_SEARCH);
    String arguments = sampleArguments(MavisCapability.WEB_SEARCH);

    // 第一次调用：应产生 1 次 catalog 请求 + 1 次 invoke 请求
    ToolCall call1 = new ToolCall("call-first", tool.descriptor().name(), arguments);
    TestToolExecutionListener listener1 = new TestToolExecutionListener();
    tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call1, Duration.ofSeconds(120)), listener1);
    assertFalse(listener1.result().error());
    assertEquals(2, transport.requests().size(), "First call should send catalog GET and MCP POST");

    // 第二次调用同能力或不同能力：catalog 请求命中缓存，只产生 1 次 invoke 请求
    Tool tool2 = tools.get(MavisCapability.LIST_VOICES);
    String arguments2 = sampleArguments(MavisCapability.LIST_VOICES);
    ToolCall call2 = new ToolCall("call-second", tool2.descriptor().name(), arguments2);
    TestToolExecutionListener listener2 = new TestToolExecutionListener();
    tool2.execute(
        new ToolExecutionRequest(tool2.descriptor(), call2, Duration.ofSeconds(120)), listener2);
    assertFalse(listener2.result().error());

    assertEquals(
        3,
        transport.requests().size(),
        "Second call must reuse cached catalog; total requests should be 3 (1 catalog + 2 invokes)");

    long catalogRequests =
        transport.requests().stream()
            .filter(req -> req.url().endsWith("/mavis/api/v1/mcp/tools"))
            .count();
    assertEquals(1, catalogRequests, "Catalog /tools request must happen exactly once when cached");
  }

  /** 为 15 个能力构造满足 inputSchema 的最简合法参数 JSON。 */
  static String sampleArguments(MavisCapability capability) {
    return switch (capability) {
      case WEB_SEARCH -> "{\"query\":\"test query\"}";
      case EXTRACT_WEB -> "{\"prompt\":\"summarize\",\"urls\":[\"https://example.com/page\"]}";
      case IMAGE_SEARCH -> "{\"queries\":[\"cat image\"]}";
      case REVERSE_IMAGE -> "{\"image\":\"https://example.com/cat.jpg\"}";
      case UNDERSTAND_IMAGE -> "{\"inputs\":[\"https://example.com/cat.jpg\"]}";
      case UNDERSTAND_AUDIO -> "{\"inputs\":[\"https://example.com/sound.mp3\"]}";
      case UNDERSTAND_VIDEO -> "{\"inputs\":[\"https://example.com/clip.mp4\"]}";
      case ASR -> "{\"input\":\"https://example.com/speech.mp3\"}";
      case LIST_VOICES -> "{}";
      case TTS -> "{\"text\":\"Hello world speech\"}";
      case TTS_BATCH -> "{\"requests\":[{\"text\":\"Batch voice synthesis\"}]}";
      case GENERATE_IMAGE -> "{\"prompt\":\"A peaceful morning scene\"}";
      case GENERATE_MUSIC -> "{\"prompt\":\"A soft jazz tune\"}";
      case SUBMIT_VIDEO -> "{\"duration\":5,\"model\":\"MiniMax-H3\",\"prompt\":\"sunset over ocean\"}";
      case QUERY_VIDEO -> "{\"model\":\"MiniMax-H3\",\"task_id\":\"task-abc-123\"}";
    };
  }

  /** 记录所有 HTTP 请求并在匹配 MCP 契约时返回成功 JSON 的假传输。 */
  static final class FakeMavisTransport implements MavisHttpTransport {
    private final List<MavisHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(200, CATALOG_RESPONSE);
      }
      // 包含媒体 URL 的响应，供媒体暂存能力测试
      String responseBody =
          "{\"base_resp\":{\"status_code\":0,\"status_msg\":\"ok\"},"
              + "\"media_url\":\""
              + MOCK_MEDIA_URL
              + "\","
              + "\"result\":\"success\"}";
      return new MavisHttpResponse(200, responseBody);
    }

    public List<MavisHttpRequest> requests() {
      return requests;
    }
  }

  /** 同线程执行器，确保 Tool 执行结果同步交付，避免多线程异步等待。 */
  static final class DirectExecutor extends AbstractExecutorService {
    private volatile boolean terminated = false;

    @Override
    public void shutdown() {
      terminated = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      terminated = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return terminated;
    }

    @Override
    public boolean isTerminated() {
      return terminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }

  /** 假凭据持久化存储。 */
  static final class FakeCredentialStore implements PluginCredentialStore {
    private final PluginCredentialSnapshot snapshot;

    FakeCredentialStore(PluginCredentialSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public PluginCredentialProjection projection(String pluginId) {
      return null;
    }

    @Override
    public PluginCredentialSnapshot resolve(String pluginId) {
      return snapshot;
    }

    @Override
    public PluginCredentialProjection save(String pluginId, PluginCredentialMaterial material) {
      return null;
    }

    @Override
    public void delete(String pluginId) {}
  }

  /** 假资源网关。 */
  static final class FakeResourceGateway implements PluginResourceGateway {
    @Override
    public URI resolveSessionResource(String resourceUri) {
      return URI.create("https://stage.kkstudio.local/download/mock-file");
    }

    @Override
    public ResourceRef stageRemoteMedia(URI remoteUri, PluginMediaFamily family, String name) {
      return new ResourceRef(
          "blob-upload:" + STAGED_BLOB_ID,
          "image/png",
          name,
          1024L,
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }
  }

  /** 收集回调结果的同步测试 Listener。 */
  static final class TestToolExecutionListener implements ToolExecutionListener {
    private final AtomicReference<ToolResult> resultRef = new AtomicReference<>();
    private final AtomicReference<Throwable> errorRef = new AtomicReference<>();

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      resultRef.set(outcome.result());
    }

    @Override
    public void onError(Throwable error) {
      errorRef.set(error);
    }

    public ToolResult result() {
      return resultRef.get();
    }

    public Throwable error() {
      return errorRef.get();
    }
  }
}
