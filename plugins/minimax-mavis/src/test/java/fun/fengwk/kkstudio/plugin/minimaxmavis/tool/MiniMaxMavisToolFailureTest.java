package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;
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
import java.util.concurrent.Semaphore;
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

  /** 直接驱动资源适配公开入口所需的最小 JSON 解析器。 */
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private static final String CATALOG_RESPONSE =
      "{\"tools\":["
          + "{\"endpoint\":\"web_search\"},"
          + "{\"endpoint\":\"image_reverse_search\"},"
          + "{\"endpoint\":\"synthesize_speech\"},"
          + "{\"endpoint\":\"batch_text_to_audio\"},"
          + "{\"endpoint\":\"image_synthesize\"},"
          + "{\"endpoint\":\"batch_text_to_music\"}"
          + "]}";

  private final Semaphore executor = new Semaphore(8);
  private FakeTransport transport;
  private FakeGateway gateway;

  @BeforeEach
  void setUp() {
    this.transport = new FakeTransport();
    this.gateway = new FakeGateway();
  }

  /** 凭据 6 种不可用原因：结果为 error，文本以 MAVIS_CREDENTIAL_<REASON> 开头，且 transport 完全未被调用。 */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PluginCredentialUnavailableReason.class)
  void credentialUnavailableFailsDeterministicallyWithoutTransportCalls(
      PluginCredentialUnavailableReason reason) {
    PluginCredentialAccess credentialAccess = new FailingCredentialAccess(reason);
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-cred-fail", tool.descriptor().name(), "{\"query\":\"test\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));

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

    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-catalog-missing", tool.descriptor().name(), "{\"query\":\"test\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    // 无资源网关
    MiniMaxMavisResourceAccess accessWithoutGateway = new MiniMaxMavisResourceAccess(null);

    Tool tool =
        new MiniMaxMavisTool(
            capability,
            MavisToolDefinitions.load(capability),
            credentialAccess,
            client,
            cache,
            accessWithoutGateway,
            executor);

    String arguments = MiniMaxMavisToolExecutionTest.sampleArguments(capability);
    ToolCall call =
        new ToolCall("call-gen-" + capability.id(), tool.descriptor().name(), arguments);
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(
        tool, new ToolExecutionRequest(tool.descriptor(), call, capability.requestTimeout()));

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithoutGateway = new MiniMaxMavisResourceAccess(null);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialAccess,
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
    listener.run(tool, request);

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialAccess,
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
    listener.run(tool, request);

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialAccess,
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
    listener.run(tool, request);

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialAccess,
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
    listener.run(tool, request);

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
    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess accessWithGateway = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            credentialAccess,
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
    listener.run(tool, request);

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

    PluginCredentialAccess credentialAccess = validCredentialAccess();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolCall call =
        new ToolCall("call-timeout", tool.descriptor().name(), "{\"query\":\"test query\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));

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
   * 调用期 HTTP 401 是已证认证拒绝：结果仍是确定性 MAVIS_CALL_FAILED，但必须把**本次解析出的**凭据（同一快照实例，携带自己的 version 与原始密文）交给
   * credentialAccess 收敛，且错误文本不含 token。
   */
  @Test
  void authenticatedRejectionInvalidatesExactlyTheResolvedCredential() {
    transport.setInvokeResponse(new MavisHttpResponse(401, "{}"));
    PluginCredentialSnapshot snapshot = snapshot();
    RejectionTrackingCredentialAccess credentialAccess =
        new RejectionTrackingCredentialAccess(snapshot);

    ToolResult result = executeWebSearch(credentialAccess);

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CALL_FAILED"));
    assertFalse(resultText(result).contains(IDENTIFIABLE_SECRET_TOKEN));
    assertEquals(
        List.of(snapshot),
        credentialAccess.rejected(),
        "rejectUsed must receive exactly the credential resolved for this call");
  }

  /** 只有已证认证语义才允许作废凭据：403 + 已知认证业务码（1004）收敛为认证拒绝；403 + 非认证业务码（权限拒绝）不是凭据失效， 绝不能把权限问题升级为重新登录。 */
  @Test
  void businessAuthCodeInvalidatesButForbiddenWithoutAuthCodeDoesNot() {
    transport.setInvokeResponse(
        new MavisHttpResponse(
            403, "{\"base_resp\":{\"status_code\":1006,\"status_msg\":\"permission denied\"}}"));
    RejectionTrackingCredentialAccess forbiddenStore =
        new RejectionTrackingCredentialAccess(snapshot());
    assertTrue(executeWebSearch(forbiddenStore).error());
    assertTrue(
        forbiddenStore.rejected().isEmpty(),
        "an HTTP 403 without a known auth code must not invalidate the credential");

    transport.setInvokeResponse(
        new MavisHttpResponse(403, "{\"base_resp\":{\"status_code\":1004}}"));
    RejectionTrackingCredentialAccess rejectedStore =
        new RejectionTrackingCredentialAccess(snapshot());
    assertTrue(executeWebSearch(rejectedStore).error());
    assertEquals(1, rejectedStore.rejected().size());
  }

  /** 传输失败（断连/超时）结果不确定：绝不作废凭据，避免把一次性网络故障升级为重新登录。 */
  @Test
  void transportFailureNeverInvalidatesCredential() {
    transport.setInvokeFailure(new MavisTransportException("connect timeout to upstream provider"));
    RejectionTrackingCredentialAccess credentialAccess =
        new RejectionTrackingCredentialAccess(snapshot());

    assertTrue(executeWebSearch(credentialAccess).error());
    assertTrue(credentialAccess.rejected().isEmpty());
  }

  /**
   * 目录返回后凭据被撤销：目录使用的快照不能作为后续 POST 的许可。
   *
   * <p>POST 前必须重新解析，重解析发现授权不可用时不得发送能力请求。
   */
  @Test
  void catalogSnapshotIsNotAPostPermitWhenCredentialRevokedAfterCatalogReturns() {
    PluginCredentialSnapshot catalogSnapshot = snapshot();
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(
            catalogSnapshot,
            new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.REAUTH_REQUIRED, "revoked after catalog"));

    ToolResult result = executeWebSearch(credentialAccess);

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CREDENTIAL_REAUTH_REQUIRED"));
    assertEquals(2, credentialAccess.resolveCount(), "目录与 POST 各自解析一次");
    assertEquals(1, transport.requests().size(), "catalog 返回后撤销：不得再发送能力 POST");
    assertEquals("GET", transport.requests().get(0).method());
  }

  /** 同代际刷新：目录后 POST 前重新解析出更新的行版本，POST 必须使用新快照而不是目录快照。 */
  @Test
  void capabilityPostResolvesFreshSnapshotAfterSameGenerationRefresh() {
    PluginCredentialSnapshot catalogSnapshot = snapshot("token-catalog-generation", 7L);
    PluginCredentialSnapshot refreshedSnapshot = snapshot("token-post-refresh", 8L);
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(catalogSnapshot, refreshedSnapshot);

    ToolResult result = executeWebSearch(credentialAccess);

    assertFalse(result.error(), () -> "same-generation refresh must still succeed: " + result);
    List<MavisHttpRequest> requests = transport.requests();
    assertEquals("Bearer token-catalog-generation", requests.get(0).headers().get("Authorization"));
    MavisHttpRequest postRequest = requests.get(requests.size() - 1);
    assertEquals("POST", postRequest.method());
    assertEquals(
        "Bearer token-post-refresh",
        postRequest.headers().get("Authorization"),
        "能力 POST 必须使用本次 POST 前重新解析出的新快照");
    // 实际用于 POST 的快照是新行版本。
    assertEquals(8L, credentialAccess.resolved().get(1).version());
  }

  /** POST 被认证拒绝时只作废实际发起 POST 的快照，目录快照不受影响。 */
  @Test
  void postRejectionInvalidatesOnlyThePostSnapshot() {
    PluginCredentialSnapshot catalogSnapshot = snapshot("token-catalog-only", 7L);
    PluginCredentialSnapshot postSnapshot = snapshot("token-post-only", 8L);
    transport.setInvokeResponse(new MavisHttpResponse(401, "{}"));
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(catalogSnapshot, postSnapshot);

    ToolResult result = executeWebSearch(credentialAccess);

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CALL_FAILED"));
    assertEquals(
        List.of(postSnapshot), credentialAccess.rejected(), "只有实际发起 POST 的快照能被作废，目录快照不受影响");
  }

  /** 目录请求本身被认证拒绝时作废目录快照，且不再发送能力 POST。 */
  @Test
  void catalogRejectionInvalidatesTheCatalogSnapshot() {
    PluginCredentialSnapshot catalogSnapshot = snapshot("token-catalog-rejected", 7L);
    transport.setCatalogResponse(401, "{}");
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(catalogSnapshot, snapshot("token-post-unused", 8L));

    ToolResult result = executeWebSearch(credentialAccess);

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CALL_FAILED"));
    assertEquals(1, transport.requests().size(), "目录被拒绝时不得发送能力 POST");
    assertEquals(List.of(catalogSnapshot), credentialAccess.rejected(), "目录快照才是本次实际请求所用的凭据");
  }

  /** 目录命中缓存时仍必须为 POST 重新解析；当前授权不可用时不得发送任何请求。 */
  @Test
  void cacheHitCannotBypassCurrentAuthorization() {
    PluginCredentialSnapshot snapshot = snapshot();
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(
            snapshot,
            snapshot,
            snapshot,
            new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.EXPIRED, "expired after cache hit"));
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolResult first = executeTool(tool, "call-warm", "{\"query\":\"test\"}");
    assertFalse(first.error(), () -> "first call must succeed: " + first);
    assertEquals(2, transport.requests().size(), "首次调用应发送目录 GET 与能力 POST");

    ToolResult second = executeTool(tool, "call-cached", "{\"query\":\"test\"}");
    assertTrue(second.error());
    assertTrue(resultText(second).startsWith("MAVIS_CREDENTIAL_EXPIRED"));
    assertEquals(2, transport.requests().size(), "命中目录缓存时仍须重新解析；授权不可用时不得发送任何后续请求");
  }

  /** 多媒体输出的每一项暂存都携带同一次调用的权威授权检查（credentialAccess::resolve）。 */
  @Test
  void mediaStagingReceivesBeforeRequestForEveryItem() {
    PluginCredentialSnapshot snapshot = snapshot();
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(snapshot, snapshot, snapshot, snapshot, snapshot, snapshot);
    transport.setInvokeResponse(
        new MavisHttpResponse(
            200,
            "{\"base_resp\":{\"status_code\":0},"
                + "\"urls\":[\"https://cdn.example.com/a.png\",\"https://cdn.example.com/b.png\"]}"));
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.GENERATE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.GENERATE_IMAGE),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolResult result = executeTool(tool, "call-media", "{\"prompt\":\"a cat\"}");

    assertFalse(result.error(), () -> "multi-media output must stage successfully: " + result);
    assertEquals(2, result.contents().size(), "每个媒体地址各暂存为一个 Resource 引用");
    List<Runnable> beforeRequests = gateway.stagedBeforeRequests();
    assertEquals(2, beforeRequests.size(), "每个媒体项各带一次授权检查");
    assertEquals(6, credentialAccess.resolveCount(), "目录、POST 与每项 GET/PUT 分别检查当前凭据");
  }

  /**
   * 目录解析必须在取得 capability cache monitor 之后进行：等待该锁可能很久，期间凭据可能已被撤销；若在等待前解析，就会用旧许可发出目录请求。
   *
   * <p>测试用真实工作线程持有/等待 cache monitor，并以 {@link Thread.State#BLOCKED} 确认排队（非 sleep），再撤销凭据并释放锁。
   */
  @Test
  void catalogResolveRunsUnderCacheLockSoRevocationWhileWaitingSendsNoCatalogRequest()
      throws Exception {
    PluginCredentialSnapshot valid = snapshot();
    ToggleableCredentialAccess credentialAccess = new ToggleableCredentialAccess(valid);
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            new MiniMaxMavisResourceAccess(gateway),
            executor);
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    ToolCall call = new ToolCall("call-lock", tool.descriptor().name(), "{\"query\":\"test\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120));

    Thread caller;
    synchronized (cache) {
      caller = Thread.ofVirtual().start(() -> listener.run(tool, request));
      awaitBlocked(caller, "capability cache monitor");
      credentialAccess.revoke();
    }
    caller.join(10_000);

    ToolResult result = listener.result();
    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CREDENTIAL_REAUTH_REQUIRED"));
    assertTrue(transport.requests().isEmpty(), "等待 cache 锁期间被撤销的凭据不得再发出目录请求");
  }

  /** 多媒输出：第 2 项在出网检查时被撤销，其后项不再暂存，且结果保持凭据类型而不是普通资源错误。 */
  @Test
  void stagingStopsAtTheItemWhoseBeforeRequestIsRevoked() {
    PluginCredentialSnapshot valid = snapshot();
    SequencedCredentialAccess credentialAccess =
        new SequencedCredentialAccess(
            valid,
            valid,
            valid,
            valid,
            valid,
            new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.REAUTH_REQUIRED, "revoked on the second item"));
    transport.setInvokeResponse(
        new MavisHttpResponse(
            200,
            "{\"base_resp\":{\"status_code\":0},\"urls\":["
                + "\"https://cdn.example.com/1.png\","
                + "\"https://cdn.example.com/2.png\","
                + "\"https://cdn.example.com/3.png\"]}"));
    MavisClient client = new MavisClient(transport);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.GENERATE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.GENERATE_IMAGE),
            credentialAccess,
            client,
            new MiniMaxMavisCapabilityCache(client),
            new MiniMaxMavisResourceAccess(gateway),
            executor);

    ToolResult result = executeTool(tool, "call-multi", "{\"prompt\":\"x\"}");

    assertTrue(result.error());
    assertTrue(
        resultText(result).startsWith("MAVIS_CREDENTIAL_REAUTH_REQUIRED"),
        () -> "第 2 项撤销必须保持凭据类型，实际: " + resultText(result));
    assertEquals(2, gateway.stagedRemoteUris().size(), "第 2 项被拒绝后不得再暂存第 3 项");
    assertEquals(6, credentialAccess.resolveCount(), "第 2 项 PUT 检查失败后不得再申请许可");
  }

  /** 轮询直到工作线程进入 BLOCKED：条件确定且有界，不使用 sleep 弱证明。 */
  private static void awaitBlocked(Thread worker, String description) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      if (worker.getState() == Thread.State.BLOCKED) {
        return;
      }
      if (System.nanoTime() > deadline) {
        fail("worker thread did not block on " + description);
      }
      Thread.onSpinWait();
    }
  }

  private ToolResult executeTool(Tool tool, String id, String arguments) {
    ToolCall call = new ToolCall(id, tool.descriptor().name(), arguments);
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));
    return listener.result();
  }

  /** 冻结能力对外可见，是启动期静态定义的稳定投影。 */
  @Test
  void capabilityGetterExposesFrozenCapability() {
    MiniMaxMavisTool tool =
        new MiniMaxMavisTool(
            MavisCapability.TTS,
            MavisToolDefinitions.load(MavisCapability.TTS),
            validCredentialAccess(),
            new MavisClient(transport),
            new MiniMaxMavisCapabilityCache(new MavisClient(transport)),
            new MiniMaxMavisResourceAccess(gateway),
            executor);

    assertEquals(MavisCapability.TTS, tool.capability());
  }

  /** 纯文本能力响应超过结果预算时确定性失败，不做静默截断。 */
  @Test
  void oversizedJsonResultFailsWithResponseTooLarge() {
    transport.setInvokeResponse(
        new MavisHttpResponse(
            200,
            "{\"base_resp\":{\"status_code\":0},\"blob\":\"" + "x".repeat(600 * 1024) + "\"}"));
    Tool tool = webSearchTool(validCredentialAccess());

    ToolResult result = executeTool(tool, "call-huge", "{\"query\":\"test\"}");

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_RESPONSE_TOO_LARGE"));
  }

  /** reject 记账本身失败（并发重新登录已换 token）时不得掩盖认证拒绝，也不泄露秘密。 */
  @Test
  void rejectionBookkeepingFailureDoesNotMaskAuthResult() {
    transport.setInvokeResponse(new MavisHttpResponse(401, "{}"));
    Tool tool = webSearchTool(new ThrowingRejectCredentialAccess(snapshot()));

    ToolResult result = executeTool(tool, "call-reject-fail", "{\"query\":\"test\"}");

    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_CALL_FAILED"));
    assertFalse(resultText(result).contains(IDENTIFIABLE_SECRET_TOKEN));
  }

  /** 媒体暂存的资源错误收敛为 MAVIS_RESOURCE_UNAVAILABLE；非预期运行时错误收敛为 MAVIS_CALL_FAILED。 */
  @Test
  void stagingFailuresAreReportedWithDeterministicCodes() {
    transport.setInvokeResponse(
        new MavisHttpResponse(
            200, "{\"base_resp\":{\"status_code\":0},\"url\":\"https://cdn.example.com/a.png\"}"));

    Tool resourceFailureTool =
        generateImageTool(
            validCredentialAccess(),
            new FailingStageGateway(new PluginResourceUnavailableException("staging rejected")));
    ToolResult resourceFailure =
        executeTool(resourceFailureTool, "call-stage-res", "{\"prompt\":\"x\"}");
    assertTrue(resourceFailure.error());
    assertTrue(resultText(resourceFailure).startsWith("MAVIS_RESOURCE_UNAVAILABLE"));

    Tool runtimeFailureTool =
        generateImageTool(
            validCredentialAccess(), new FailingStageGateway(new IllegalStateException("boom")));
    ToolResult runtimeFailure =
        executeTool(runtimeFailureTool, "call-stage-rt", "{\"prompt\":\"x\"}");
    assertTrue(runtimeFailure.error());
    assertTrue(resultText(runtimeFailure).startsWith("MAVIS_CALL_FAILED"));
  }

  /** 媒体能力响应对应不到任何可暂存地址时回退为有界 JSON 结果，不产生空暂存。 */
  @Test
  void mediaStagingFallsBackToJsonWhenResponseCarriesNoMedia() {
    transport.setInvokeResponse(
        new MavisHttpResponse(200, "{\"base_resp\":{\"status_code\":0},\"note\":\"no media\"}"));
    Tool tool = reverseImageTool(validCredentialAccess());

    ToolResult result =
        executeTool(tool, "call-nomedia", "{\"image\":\"https://example.com/cat.jpg\"}");

    assertFalse(result.error(), () -> "no-media response must still succeed: " + result);
    assertTrue(gateway.stagedBeforeRequests().isEmpty(), "无媒体地址时不得暂存");
  }

  /** 媒体地址收集按固定上限截断，避免一次响应触发无界暂存。 */
  @Test
  void mediaUrlCollectionIsCappedAtConfiguredMaximum() {
    StringBuilder urls = new StringBuilder("[");
    for (int index = 0; index < 9; index++) {
      urls.append(index > 0 ? "," : "")
          .append("\"https://cdn.example.com/")
          .append(index)
          .append(".png\"");
    }
    urls.append("]");
    transport.setInvokeResponse(
        new MavisHttpResponse(200, "{\"base_resp\":{\"status_code\":0},\"urls\":" + urls + "}"));
    Tool tool = reverseImageTool(validCredentialAccess());

    ToolResult result =
        executeTool(tool, "call-cap", "{\"image\":\"https://example.com/cat.jpg\"}");

    assertFalse(result.error());
    assertEquals(8, gateway.stagedBeforeRequests().size(), "单次调用最多暂存 8 项");
    assertEquals(8, result.contents().size());
  }

  /** 形态无效的 https 候选地址被忽略，不影响同响应里的合法地址。 */
  @Test
  void malformedHttpsCandidateIsIgnoredDuringCollection() {
    transport.setInvokeResponse(
        new MavisHttpResponse(
            200,
            "{\"base_resp\":{\"status_code\":0},\"urls\":["
                + "\"https://cdn.example.com/ok.png\","
                + "\"https://exa mple.com/bad.png\"]}"));
    Tool tool = reverseImageTool(validCredentialAccess());

    ToolResult result =
        executeTool(tool, "call-badurl", "{\"image\":\"https://example.com/cat.jpg\"}");

    assertFalse(result.error());
    assertEquals(1, gateway.stagedBeforeRequests().size(), "无法解析的候选地址必须被忽略");
  }

  /** 参数里的 session 资源按数组/对象结构递归解析，非资源取值原样保留（直接驱动资源适配的公开入口）。 */
  @Test
  void resolveInputsRecursivelyResolvesNestedSessionResources() throws Exception {
    String resource = "kkstudio:/resources/" + VALID_BLOB_UUID;
    JsonNode arguments =
        JSON.readTree(
            "{\"image\":\""
                + resource
                + "\",\"gallery\":[\""
                + resource
                + "\",{\"nested\":\""
                + resource
                + "\"}],\"count\":3,\"note\":\"plain\"}");
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);
    UUID threadId = UUID.fromString("33333333-4444-5555-6666-777777777777");

    JsonNode resolved = access.resolveInputs(threadId, arguments);

    String signed = "https://resolved.files.local/" + VALID_BLOB_UUID;
    assertEquals(signed, resolved.get("image").textValue());
    assertEquals(signed, resolved.get("gallery").get(0).textValue());
    assertEquals(signed, resolved.get("gallery").get(1).get("nested").textValue());
    assertEquals("plain", resolved.get("note").textValue());
    assertEquals(3, gateway.resolveCallCount(), "每个 session 资源引用各解析一次");
  }

  /** 网关交回不可用的下载地址时必须在发送能力请求前确定性失败。 */
  @Test
  void unusableResolvedDownloadUriFailsBeforeSend() {
    MavisClient client = new MavisClient(transport);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.REVERSE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
            validCredentialAccess(),
            client,
            new MiniMaxMavisCapabilityCache(client),
            new MiniMaxMavisResourceAccess(new HttpDownloadGateway()),
            executor);
    String arguments = "{\"image\":\"kkstudio:/resources/" + VALID_BLOB_UUID + "\"}";
    ToolCall call = new ToolCall("call-bad-download", tool.descriptor().name(), arguments);
    UUID threadId = UUID.fromString("44444444-5555-6666-7777-888888888888");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(
        tool,
        new ToolExecutionRequest(
            tool.descriptor(), call, Duration.ofSeconds(120), createContext(threadId)));

    ToolResult result = listener.result();
    assertTrue(result.error());
    assertTrue(resultText(result).startsWith("MAVIS_RESOURCE_UNAVAILABLE"));
    assertTrue(transport.requests().isEmpty(), "下载地址不可用时不得发送能力请求");
  }

  private Tool webSearchTool(PluginCredentialAccess credentialAccess) {
    MavisClient client = new MavisClient(transport);
    return new MiniMaxMavisTool(
        MavisCapability.WEB_SEARCH,
        MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
        credentialAccess,
        client,
        new MiniMaxMavisCapabilityCache(client),
        new MiniMaxMavisResourceAccess(gateway),
        executor);
  }

  private Tool reverseImageTool(PluginCredentialAccess credentialAccess) {
    MavisClient client = new MavisClient(transport);
    return new MiniMaxMavisTool(
        MavisCapability.REVERSE_IMAGE,
        MavisToolDefinitions.load(MavisCapability.REVERSE_IMAGE),
        credentialAccess,
        client,
        new MiniMaxMavisCapabilityCache(client),
        new MiniMaxMavisResourceAccess(gateway),
        executor);
  }

  private Tool generateImageTool(
      PluginCredentialAccess credentialAccess, PluginResourceGateway resourceGateway) {
    MavisClient client = new MavisClient(transport);
    return new MiniMaxMavisTool(
        MavisCapability.GENERATE_IMAGE,
        MavisToolDefinitions.load(MavisCapability.GENERATE_IMAGE),
        credentialAccess,
        client,
        new MiniMaxMavisCapabilityCache(client),
        new MiniMaxMavisResourceAccess(resourceGateway),
        executor);
  }

  private ToolResult executeWebSearch(PluginCredentialAccess credentialAccess) {
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.WEB_SEARCH,
            MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
            credentialAccess,
            client,
            cache,
            access,
            executor);

    ToolCall call = new ToolCall("call-auth", tool.descriptor().name(), "{\"query\":\"test\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));
    return listener.result();
  }

  private static PluginCredentialSnapshot snapshot() {
    return snapshot(IDENTIFIABLE_SECRET_TOKEN, 7L);
  }

  /** 指定 token 与行版本的快照：用于构造「已刷新」的新版本。 */
  private static PluginCredentialSnapshot snapshot(String token, long version) {
    return new PluginCredentialSnapshot(
        MiniMaxMavisPlugin.PLUGIN_ID,
        MavisRegion.CN.id().toUpperCase(),
        Instant.now().plusSeconds(3600),
        version,
        new MiniMaxMavisCredentialPayload(token, CLIENT_UUID, Instant.now()).toJson());
  }

  /**
   * 调用并发上限已满（许可耗尽）：必须在**发送请求前**收敛为确定性的 MAVIS_CALL_FAILED，且不产生任何副作用。
   *
   * <p>这是「额度型调用宁可失败也不排队重放」的边界证据：拒绝发生在任何网络请求之前，因此它既不是不确定失败，也不需要用户重新登录。
   */
  @Test
  void exhaustedPermitsFailDeterministicallyWithoutSideEffects() {
    Semaphore exhausted = new Semaphore(0);
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache cache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);

    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.GENERATE_IMAGE,
            MavisToolDefinitions.load(MavisCapability.GENERATE_IMAGE),
            validCredentialAccess(),
            client,
            cache,
            access,
            exhausted);

    ToolCall call =
        new ToolCall(
            "call-rejected", tool.descriptor().name(), "{\"prompt\":\"a cat on the moon\"}");
    MiniMaxMavisToolExecutionTest.TestToolExecutionListener listener =
        new MiniMaxMavisToolExecutionTest.TestToolExecutionListener();
    listener.run(tool, new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)));

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

  private PluginCredentialAccess validCredentialAccess() {
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            MiniMaxMavisPlugin.PLUGIN_ID,
            MavisRegion.CN.id().toUpperCase(),
            Instant.now().plusSeconds(3600),
            0L,
            new MiniMaxMavisCredentialPayload(IDENTIFIABLE_SECRET_TOKEN, CLIENT_UUID, Instant.now())
                .toJson());
    return new MiniMaxMavisToolExecutionTest.FakeCredentialAccess(snapshot);
  }

  /** 产生凭据不可用异常的假 scoped 凭据访问。 */
  static final class FailingCredentialAccess implements PluginCredentialAccess {
    private final PluginCredentialUnavailableReason reason;

    FailingCredentialAccess(PluginCredentialUnavailableReason reason) {
      this.reason = reason;
    }

    @Override
    public PluginCredentialSnapshot resolve() {
      throw new PluginCredentialUnavailableException(
          reason, "Credential unavailable due to: " + reason);
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return false;
    }
  }

  /** 记录 rejectUsed 入参的假 scoped 凭据访问。 */
  static final class RejectionTrackingCredentialAccess implements PluginCredentialAccess {
    private final PluginCredentialSnapshot snapshot;
    private final List<PluginCredentialSnapshot> rejected =
        Collections.synchronizedList(new ArrayList<>());

    RejectionTrackingCredentialAccess(PluginCredentialSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public PluginCredentialSnapshot resolve() {
      return snapshot;
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      rejected.add(snapshot);
      return true;
    }

    List<PluginCredentialSnapshot> rejected() {
      return List.copyOf(rejected);
    }
  }

  /** 按次序返回快照或抛出凭据错误；额外解析会使测试失败。 */
  static final class SequencedCredentialAccess implements PluginCredentialAccess {
    private final List<Object> outcomes;
    private final List<PluginCredentialSnapshot> resolved =
        Collections.synchronizedList(new ArrayList<>());
    private final List<PluginCredentialSnapshot> rejected =
        Collections.synchronizedList(new ArrayList<>());
    private int index = 0;

    SequencedCredentialAccess(Object... outcomes) {
      this.outcomes = List.of(outcomes);
    }

    @Override
    public synchronized PluginCredentialSnapshot resolve() {
      Object outcome = outcomes.get(index);
      index++;
      if (outcome instanceof PluginCredentialSnapshot snapshot) {
        resolved.add(snapshot);
        return snapshot;
      }
      throw (PluginCredentialUnavailableException) outcome;
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      rejected.add(snapshot);
      return true;
    }

    synchronized int resolveCount() {
      return index;
    }

    List<PluginCredentialSnapshot> resolved() {
      return List.copyOf(resolved);
    }

    List<PluginCredentialSnapshot> rejected() {
      return List.copyOf(rejected);
    }
  }

  /** rejectUsed 抛异常的假 scoped 凭据访问：验证记账失败不掩盖认证拒绝结果。 */
  static final class ThrowingRejectCredentialAccess implements PluginCredentialAccess {
    private final PluginCredentialSnapshot snapshot;

    ThrowingRejectCredentialAccess(PluginCredentialSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public PluginCredentialSnapshot resolve() {
      return snapshot;
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      throw new IllegalStateException("concurrent re-login replaced the credential");
    }
  }

  /** 暂存阶段抛指定运行时错误的假网关。 */
  static final class FailingStageGateway implements PluginResourceGateway {
    private final RuntimeException failure;

    FailingStageGateway(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public URI resolveSessionResource(UUID threadId, String resourceUri) {
      return URI.create("https://stage.local/download/mock-file");
    }

    @Override
    public ResourceRef stageRemoteMedia(
        URI remoteUri, PluginMediaFamily family, String name, Runnable beforeRequest) {
      throw failure;
    }
  }

  /** 交回非 https 下载地址的假网关：验证解析结果必须可安全使用。 */
  static final class HttpDownloadGateway implements PluginResourceGateway {

    @Override
    public URI resolveSessionResource(UUID threadId, String resourceUri) {
      return URI.create("http://insecure.local/download/mock-file");
    }

    @Override
    public ResourceRef stageRemoteMedia(
        URI remoteUri, PluginMediaFamily family, String name, Runnable beforeRequest) {
      throw new UnsupportedOperationException("this test never stages media");
    }
  }

  /** 支持预设 catalog 响应和注入调用异常的假传输。 */
  static final class FakeTransport implements MavisHttpTransport {
    private final List<MavisHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private String catalogResponse = CATALOG_RESPONSE;
    private int catalogStatus = 200;
    private MavisTransportException invokeFailure;
    private MavisHttpResponse invokeResponse;

    void setCatalogResponse(String catalogResponse) {
      this.catalogResponse = catalogResponse;
    }

    void setCatalogResponse(int catalogStatus, String catalogResponse) {
      this.catalogStatus = catalogStatus;
      this.catalogResponse = catalogResponse;
    }

    void setInvokeFailure(MavisTransportException invokeFailure) {
      this.invokeFailure = invokeFailure;
    }

    void setInvokeResponse(MavisHttpResponse invokeResponse) {
      this.invokeResponse = invokeResponse;
    }

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(catalogStatus, catalogResponse);
      }
      if (invokeFailure != null) {
        throw invokeFailure;
      }
      if (invokeResponse != null) {
        return invokeResponse;
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
    private final List<Runnable> stagedBeforeRequests =
        Collections.synchronizedList(new ArrayList<>());
    private final List<String> stagedRemoteUris = Collections.synchronizedList(new ArrayList<>());

    @Override
    public URI resolveSessionResource(UUID threadId, String resourceUri) {
      resolvedThreadIds.add(threadId);
      resolvedUris.add(resourceUri);
      String id = resourceUri.substring("kkstudio:/resources/".length());
      return URI.create("https://resolved.files.local/" + id);
    }

    @Override
    public ResourceRef stageRemoteMedia(
        URI remoteUri, PluginMediaFamily family, String name, Runnable beforeRequest) {
      stagedRemoteUris.add(remoteUri.toASCIIString());
      stagedBeforeRequests.add(beforeRequest);
      // 模拟真实网关：每一项在出网 GET 与 PUT 之前各执行一次宿主授权检查。
      beforeRequest.run();
      beforeRequest.run();
      return new ResourceRef(
          "blob-upload:" + VALID_BLOB_UUID,
          "image/png",
          name,
          1024L,
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    List<Runnable> stagedBeforeRequests() {
      return List.copyOf(stagedBeforeRequests);
    }

    List<String> stagedRemoteUris() {
      return List.copyOf(stagedRemoteUris);
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

  /** 可撤销的假 scoped 凭据访问：撤销后 resolve 抛出确定性的不可用，模拟调用中途凭据失效。 */
  static final class ToggleableCredentialAccess implements PluginCredentialAccess {
    private volatile PluginCredentialSnapshot snapshot;

    ToggleableCredentialAccess(PluginCredentialSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    void revoke() {
      this.snapshot = null;
    }

    @Override
    public PluginCredentialSnapshot resolve() {
      PluginCredentialSnapshot current = snapshot;
      if (current == null) {
        throw new PluginCredentialUnavailableException(
            PluginCredentialUnavailableReason.REAUTH_REQUIRED, "credential revoked");
      }
      return current;
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return true;
    }
  }
}
