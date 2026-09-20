package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
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
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisTransportException;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCapabilityCache;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCredentialPayload;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisPlugin;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisResourceAccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * MiniMax Mavis 工具失败语义与防御边界测试。
 *
 * <p>覆盖以下确定性失败形态：
 *
 * <ul>
 *   <li>凭据 6 种不可用原因收敛为对应 MAVIS_CREDENTIAL_<REASON>，且不调用任何 transport；
 *   <li>catalog 缺少当前 endpoint 收敛为 MAVIS_CAPABILITY_UNAVAILABLE，且未向 MCP endpoint 发送调用；
 *   <li>非幂等生成能力在无网关时于发送前失败，收敛为 MAVIS_RESOURCE_UNAVAILABLE，transport 完全未被调用；
 *   <li>kkstudio:/resources/<uuid> 参数在无网关、有网关和非法形态下的确定性行为；
 *   <li>网络传输异常或超时收敛为 MAVIS_CALL_FAILED，且去敏机制确保错误信息绝不泄露 token 明文。
 * </ul>
 */
class MiniMaxMavisToolFailureTest {

  private static final String IDENTIFIABLE_SECRET_TOKEN = "super-secret-mavis-token-xyz-98765";
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";
  private static final String VALID_BLOB_UUID = "11111111-2222-3333-4444-555555555555";
  private static final String CATALOG_RESPONSE =
      "{\"tools\":["
          + "{\"endpoint\":\"web_search\"},"
          + "{\"endpoint\":\"image_reverse_search\"},"
          + "{\"endpoint\":\"synthesize_speech\"},"
          + "{\"endpoint\":\"batch_text_to_audio\"},"
          + "{\"endpoint\":\"image_synthesize\"},"
          + "{\"endpoint\":\"batch_text_to_music\"}"
          + "]}";

  private DirectExecutor executor;
  private FakeTransport transport;
  private FakeGateway gateway;

  @BeforeEach
  void setUp() {
    this.executor = new DirectExecutor();
    this.transport = new FakeTransport();
    this.gateway = new FakeGateway();
  }

  /** 凭据 6 种不可用原因：结果为 error，文本以 MAVIS_CREDENTIAL_<REASON> 开头，且 transport 完全未被调用。 */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PluginCredentialUnavailableReason.class)
  void credentialUnavailableFailsDeterministicallyWithoutTransportCalls(
      PluginCredentialUnavailableReason reason) {
    PluginCredentialStore credentialStore = new FailingCredentialStore(reason);
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialStore,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-cred-fail", tool.descriptor().name(), "{\"query\":\"test\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);

    ToolResult result = listener.result();
    assertTrue(result != null, "Tool execution must invoke listener");
    assertTrue(result.error(), "Result must be error");
    String text = resultText(result);
    String expectedPrefix = "MAVIS_CREDENTIAL_" + reason.name();
    assertTrue(
        text.startsWith(expectedPrefix),
        () -> "Error message must start with " + expectedPrefix + ", actual: " + text);

    assertEquals(
        0,
        transport.requests().size(),
        "Transport must not be invoked when credentials are unavailable");
  }

  /** capability catalog 缺少当前 endpoint：结果为 MAVIS_CAPABILITY_UNAVAILABLE，且未向 MCP endpoint 发出请求。 */
  @Test
  void missingEndpointInCatalogFailsWithCapabilityUnavailable() {
    // 目录返回不包含 web_search 的列表
    transport.setCatalogResponse("{\"tools\":[{\"endpoint\":\"synthesize_speech\"}]}");

    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialStore,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-catalog-missing", tool.descriptor().name(), "{\"query\":\"test\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);

    ToolResult result = listener.result();
    assertTrue(result.error());
    String text = resultText(result);
    assertTrue(
        text.startsWith("MAVIS_CAPABILITY_UNAVAILABLE"),
        () -> "Error message must start with MAVIS_CAPABILITY_UNAVAILABLE, actual: " + text);

    long mcpRequests =
        transport.requests().stream()
            .filter(req -> req.url().contains("/mavis/api/v1/mcp/web_search"))
            .count();
    assertEquals(
        0, mcpRequests, "Must not send MCP invoke request when endpoint is missing in catalog");
  }

  /** 非幂等生成能力（tts/tts-batch/generate-image/generate-music）在没有绑定资源网关时，在发送前失败且 transport 未被调用。 */
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = MavisCapability.class,
      names = {"TTS", "TTS_BATCH", "GENERATE_IMAGE", "GENERATE_MUSIC"})
  void nonIdempotentGenerationFailsBeforeSendWhenGatewayUnbound(MavisCapability capability) {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    // 无资源网关
    MiniMaxMavisResourceAccess accessWithoutGateway = new MiniMaxMavisResourceAccess(null);

    Tool tool =
        new MiniMaxMavisTool(
            capability,
            MavisToolDefinitions.load(capability),
            credentialStore,
            client,
            cache,
            accessWithoutGateway,
            executor);

    String arguments = MiniMaxMavisToolExecutionTest.sampleArguments(capability);
    ToolCall call =
        new ToolCall("call-gen-" + capability.id(), tool.descriptor().name(), arguments);
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, capability.requestTimeout()), listener);

    ToolResult result = listener.result();
    assertTrue(result.error());
    String text = resultText(result);
    assertTrue(
        text.startsWith("MAVIS_RESOURCE_UNAVAILABLE"),
        () -> "Error message must start with MAVIS_RESOURCE_UNAVAILABLE, actual: " + text);

    assertEquals(
        0,
        transport.requests().size(),
        "Transport must not be invoked at all when resource gateway is unbound before sending");
  }

  /**
   * 参数包含 kkstudio:/resources/<uuid>： 无网关 → 即使携带 invocation context 也确定性以 MAVIS_RESOURCE_UNAVAILABLE
   * 失败且不发送网络请求。
   */
  @Test
  void sessionResourceWithoutGatewayFailsDeterministicallyWithoutTransport() {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithoutGateway = new MiniMaxMavisResourceAccess(null);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialStore,
            client,
            cache,
            accessWithoutGateway,
            executor);

    String arguments = "{\"image\":\"kkstudio:/resources/" + VALID_BLOB_UUID + "\"}";
    ToolCall call = new ToolCall("call-res-nogw", tool.descriptor().name(), arguments);
    UUID threadId = UUID.fromString("22222222-3333-4444-5555-666666666666");
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(), call, Duration.ofSeconds(120), createContext(threadId));
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertTrue(result.error());
    String text = resultText(result);
    assertTrue(text.startsWith("MAVIS_RESOURCE_UNAVAILABLE"));
    assertEquals(
        0, transport.requests().size(), "Transport must not be called when gateway is missing");
  }

  /**
   * 参数包含合法 kkstudio:/resources/<uuid>： 有网关 + context + 合法 URI → 请求正常执行成功； 发送给远端的 request body
   * 中使用解析后的 HTTPS URL 且不出现原始 kkstudio: 引用； 且断言调用了资源网关恰好一次。
   */
  @Test
  void sessionResourceWithGatewayIsResolvedToHttpsInRequestBody() {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialStore,
            client,
            cache,
            accessWithGateway,
            executor);

    UUID threadId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    String arguments = "{\"image\":\"kkstudio:/resources/" + VALID_BLOB_UUID + "\"}";
    ToolCall call = new ToolCall("call-res-resolved", tool.descriptor().name(), arguments);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(), call, Duration.ofSeconds(120), createContext(threadId));
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertFalse(
        result.error(),
        () -> "Tool execution should succeed when gateway resolves resource: " + result);

    assertEquals(1, gateway.resolveCallCount(), "Gateway must be called exactly once");

    List<MavisHttpRequest> requests = transport.requests();
    MavisHttpRequest invokeRequest = requests.get(requests.size() - 1);
    assertTrue(
        invokeRequest.body().contains("https://resolved.files.local/" + VALID_BLOB_UUID),
        "Request body must contain resolved https URL");
    assertFalse(
        invokeRequest.body().contains("kkstudio:/resources/"),
        "Request body must not contain raw session resource reference");
  }

  /**
   * 正确传递 threadId： 验证 Tool 会将 ToolExecutionContext 中的 threadId 显式传递给
   * PluginResourceGateway.resolveSessionResource； 假网关记录收到的 threadId，断言其严格等于构造的固定 UUID（非 null、非其它
   * id），且收到的 resourceUri 严格匹配。
   */
  @Test
  void sessionResourceExplicitlyPassesThreadIdToResourceGateway() {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialStore,
            client,
            cache,
            accessWithGateway,
            executor);

    UUID expectedThreadId = UUID.fromString("98765432-abcd-ef01-2345-6789abcdef01");
    String resourceUri = "kkstudio:/resources/" + VALID_BLOB_UUID;
    String arguments = "{\"image\":\"" + resourceUri + "\"}";
    ToolCall call = new ToolCall("call-thread-id-check", tool.descriptor().name(), arguments);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(), call, Duration.ofSeconds(120), createContext(expectedThreadId));
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertFalse(result.error(), () -> "Tool execution should succeed: " + result);

    assertEquals(1, gateway.resolveCallCount(), "Gateway must be called exactly once");
    assertEquals(
        expectedThreadId,
        gateway.lastResolvedThreadId(),
        "Gateway must receive the exact threadId from ToolExecutionContext");
    assertEquals(
        resourceUri,
        gateway.lastResolvedUri(),
        "Gateway must receive the exact session resource URI");
  }

  /**
   * 有网关但 context 缺失： 参数包含 kkstudio:/resources/<canonical-uuid> 且请求不带 context（使用 3 参构造
   * ToolExecutionRequest）时， 必须在发送前确定性失败，结果为 error 且错误信息以 MAVIS_RESOURCE_UNAVAILABLE 开头； transport
   * 完全没有被调用（包括目录 tools 请求），且假网关的 resolveSessionResource 完全未被调用。
   */
  @Test
  void sessionResourceWithGatewayButMissingContextFailsBeforeSend() {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialStore,
            client,
            cache,
            accessWithGateway,
            executor);

    String arguments = "{\"image\":\"kkstudio:/resources/" + VALID_BLOB_UUID + "\"}";
    ToolCall call = new ToolCall("call-res-no-ctx", tool.descriptor().name(), arguments);
    // 3 参构造，无 context (request.context() == null)
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120));
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertTrue(result != null, "Execution must complete");
    assertTrue(result.error(), "Missing context must result in an error");
    String text = resultText(result);
    assertTrue(
        text.startsWith("MAVIS_RESOURCE_UNAVAILABLE"),
        () -> "Error message must start with MAVIS_RESOURCE_UNAVAILABLE, actual: " + text);
    assertTrue(
        text.contains("session resource access requires an invocation thread id"),
        () -> "Error message should convey that thread id is required, actual: " + text);

    assertEquals(
        0,
        transport.requests().size(),
        "Transport must not be invoked at all (not even catalog tools) when context is missing");
    assertEquals(
        0,
        gateway.resolveCallCount(),
        "Gateway resolveSessionResource must not be invoked when context is missing");
  }

  /**
   * 参数中包含非法形态的 kkstudio 资源（大写 UUID、多余 path、非 resources scheme）： 即便调用请求携带了合法的 invocation
   * context，在发送请求前也必须因不满足规范 URI 形态而确定性失败； 结果为 MAVIS_RESOURCE_UNAVAILABLE，网关未被调用，transport 也未被调用。
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "kkstudio:/resources/11111111-2222-3333-4444-55555555555A", // 大写字母
        "kkstudio:/resources/11111111-2222-3333-4444-555555555555/extra", // 多余 path
        "kkstudio:/other/11111111-2222-3333-4444-555555555555" // 非 resources 路径
      })
  void malformedSessionResourceUriFailsDeterministically(String malformedUri) {
    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialStore,
            client,
            cache,
            accessWithGateway,
            executor);

    String arguments = "{\"image\":\"" + malformedUri + "\"}";
    ToolCall call = new ToolCall("call-res-bad", tool.descriptor().name(), arguments);
    UUID threadId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(), call, Duration.ofSeconds(120), createContext(threadId));
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(request, listener);

    ToolResult result = listener.result();
    assertTrue(result.error());
    String text = resultText(result);
    assertTrue(
        text.startsWith("MAVIS_RESOURCE_UNAVAILABLE"),
        () ->
            "Malformed session resource URI must fail with MAVIS_RESOURCE_UNAVAILABLE, actual: "
                + text);
    assertTrue(
        text.contains("unsupported session resource reference"),
        () -> "Error message should indicate unsupported reference, actual: " + text);

    long mcpRequests =
        transport.requests().stream()
            .filter(req -> req.url().contains("/mavis/api/v1/mcp/image_reverse_search"))
            .count();
    assertEquals(0, mcpRequests, "MCP request must not be sent on malformed session resource");
    assertEquals(
        0,
        gateway.resolveCallCount(),
        "Gateway resolveSessionResource must not be invoked on malformed session resource");
  }

  /** transport 抛出传输错误 / 超时：error 文本以 MAVIS_CALL_FAILED 开头，且失败文本不包含 token 明文。 */
  @Test
  void transportExceptionFailsWithMavisCallFailedAndRedactsToken() {
    transport.setInvokeFailure(new MavisTransportException("connect timeout to upstream provider"));

    PluginCredentialStore credentialStore = validCredentialStore();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialStore,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-timeout", tool.descriptor().name(), "{\"query\":\"test query\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);

    ToolResult result = listener.result();
    assertTrue(result.error());
    String text = resultText(result);
    assertTrue(
        text.startsWith("MAVIS_CALL_FAILED"),
        () -> "Error message must start with MAVIS_CALL_FAILED, actual: " + text);
    assertFalse(
        text.contains(IDENTIFIABLE_SECRET_TOKEN),
        "Error message must never leak access token in plain text");
  }

  /**
   * 调用并发上限已满（Executor 拒绝排入）：必须在**发送请求前**收敛为确定性的 MAVIS_CALL_FAILED，且不产生任何副作用。
   *
   * <p>这是「额度型调用宁可失败也不排队重放」的边界证据：拒绝发生在任何网络请求之前，因此它既不是不确定失败，也不需要用户重新登录。
   */
  @Test
  void rejectedExecutionFailsDeterministicallyWithoutSideEffects() {
    RejectingExecutor rejectingExecutor = new RejectingExecutor();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.GENERATE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.GENERATE_IMAGE),
            validCredentialStore(),
            client,
            cache,
            access,
            rejectingExecutor);

    ToolCall call =
        new ToolCall(
            "call-rejected", tool.descriptor().name(), "{\"prompt\":\"a cat on the moon\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);

    assertTrue(handle != null);
    ToolResult result = listener.result();
    assertTrue(result.error(), "Rejected scheduling must produce an error result");
    assertTrue(resultText(result).startsWith("MAVIS_CALL_FAILED"));
    assertTrue(
        transport.requests().isEmpty(),
        "Rejected scheduling must not send any request or consume provider quota");
  }

  private static String resultText(ToolResult result) {
    assertFalse(result.contents().isEmpty(), "Error result must have content");
    assertInstanceOf(TextResultContent.class, result.contents().get(0));
    return ((TextResultContent) result.contents().get(0)).text();
  }

  private static ToolExecutionContext createContext(UUID threadId) {
    return new ToolExecutionContext(
        UUID.randomUUID(), threadId, Instant.now(), mock(BranchView.class));
  }

  private PluginCredentialStore validCredentialStore() {
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            MiniMaxMavisPlugin.PLUGIN_ID,
            MavisRegion.CN.id().toUpperCase(),
            Instant.now().plusSeconds(3600),
            new MiniMaxMavisCredentialPayload(IDENTIFIABLE_SECRET_TOKEN, CLIENT_UUID, Instant.now())
                .toJson());
    return new MiniMaxMavisToolExecutionTest.FakeCredentialStore(snapshot);
  }

  /** 产生凭据不可用异常的假凭据 Store。 */
  static final class FailingCredentialStore implements PluginCredentialStore {
    private final PluginCredentialUnavailableReason reason;

    FailingCredentialStore(PluginCredentialUnavailableReason reason) {
      this.reason = reason;
    }

    @Override
    public PluginCredentialProjection projection(String pluginId) {
      return null;
    }

    @Override
    public PluginCredentialSnapshot resolve(String pluginId) {
      throw new PluginCredentialUnavailableException(
          reason, "Credential unavailable due to: " + reason);
    }

    @Override
    public PluginCredentialProjection save(String pluginId, PluginCredentialMaterial material) {
      return null;
    }

    @Override
    public void delete(String pluginId) {}
  }

  /** 支持预设 catalog 响应和注入调用异常的假传输。 */
  static final class FakeTransport implements MavisHttpTransport {
    private final List<MavisHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private String catalogResponse = CATALOG_RESPONSE;
    private MavisTransportException invokeFailure;

    void setCatalogResponse(String catalogResponse) {
      this.catalogResponse = catalogResponse;
    }

    void setInvokeFailure(MavisTransportException invokeFailure) {
      this.invokeFailure = invokeFailure;
    }

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(200, catalogResponse);
      }
      if (invokeFailure != null) {
        throw invokeFailure;
      }
      return new MavisHttpResponse(
          200,
          "{\"base_resp\":{\"status_code\":0,\"status_msg\":\"ok\"},\"media_url\":\"https://cdn.example.com/media.png\"}");
    }

    public List<MavisHttpRequest> requests() {
      return requests;
    }
  }

  /** 假资源网关。 */
  static final class FakeGateway implements PluginResourceGateway {
    private final List<UUID> resolvedThreadIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> resolvedUris = Collections.synchronizedList(new ArrayList<>());

    @Override
    public URI resolveSessionResource(UUID threadId, String resourceUri) {
      resolvedThreadIds.add(threadId);
      resolvedUris.add(resourceUri);
      String id = resourceUri.substring("kkstudio:/resources/".length());
      return URI.create("https://resolved.files.local/" + id);
    }

    @Override
    public ResourceRef stageRemoteMedia(URI remoteUri, PluginMediaFamily family, String name) {
      return new ResourceRef(
          "blob-upload:" + VALID_BLOB_UUID,
          "image/png",
          name,
          1024L,
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    public List<UUID> resolvedThreadIds() {
      return Collections.unmodifiableList(resolvedThreadIds);
    }

    public List<String> resolvedUris() {
      return Collections.unmodifiableList(resolvedUris);
    }

    public int resolveCallCount() {
      return resolvedUris.size();
    }

    public UUID lastResolvedThreadId() {
      if (resolvedThreadIds.isEmpty()) {
        return null;
      }
      return resolvedThreadIds.get(resolvedThreadIds.size() - 1);
    }

    public String lastResolvedUri() {
      if (resolvedUris.isEmpty()) {
        return null;
      }
      return resolvedUris.get(resolvedUris.size() - 1);
    }
  }

  /** 始终拒绝排入的执行器：模拟并发上限已满。 */
  static final class RejectingExecutor extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("tool concurrency limit reached");
    }
  }

  /** 同线程执行器。 */
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
}
