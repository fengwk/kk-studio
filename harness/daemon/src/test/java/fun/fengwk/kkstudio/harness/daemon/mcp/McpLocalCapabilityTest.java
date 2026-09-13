package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpCancelledException;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpException;
import fun.fengwk.kkstudio.harness.mcp.McpTimeoutException;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** McpLocalCallCapability 与 McpLocalDiscoverCapability 契约与执行测试。 */
class McpLocalCapabilityTest {

  private static final String SERVER_ID = "11111111-1111-4111-8111-111111111111";
  private static final String ENVIRONMENT_ID = "22222222-2222-4222-8222-222222222222";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ExecutorService executor;
  private FakeClient fakeClient;
  private DaemonLocalMcpManager manager;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
    fakeClient = new FakeClient();
    manager = new DaemonLocalMcpManager((config, deadline) -> fakeClient);
  }

  @AfterEach
  void tearDown() {
    manager.close();
    executor.shutdownNow();
  }

  private static String callJson(String toolName, String argumentsJson, long timeoutMillis) {
    return "{\"serverId\":\""
        + SERVER_ID
        + "\",\"configVersion\":1,\"toolName\":\""
        + toolName
        + "\",\"arguments\":"
        + argumentsJson
        + ",\"config\":{\"type\":\"local\",\"environmentId\":\""
        + ENVIRONMENT_ID
        + "\",\"command\":[\"node\",\"server.js\"],\"cwd\":\"/opt/test\","
        + "\"timeoutMillis\":"
        + timeoutMillis
        + "}}";
  }

  private static String discoverJson(long timeoutMillis) {
    return "{\"serverId\":\""
        + SERVER_ID
        + "\",\"configVersion\":1,\"config\":{\"type\":\"local\",\"environmentId\":\""
        + ENVIRONMENT_ID
        + "\",\"command\":[\"node\",\"server.js\"],\"cwd\":\"/opt/test\","
        + "\"timeoutMillis\":"
        + timeoutMillis
        + "}}";
  }

  private static EnvironmentCapabilityResult execute(
      EnvironmentCapability capability,
      String callId,
      String argumentsJson,
      Duration requestTimeout)
      throws Exception {
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall(callId, argumentsJson),
            requestTimeout);
    CompletableFuture<EnvironmentCapabilityResult> future = new CompletableFuture<>();
    capability.execute(
        request,
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            future.complete(result);
          }

          @Override
          public void onError(Throwable error) {
            future.completeExceptionally(error);
          }
        });
    return future.get(15, TimeUnit.SECONDS);
  }

  /** 工具结果按原义透传：内容结构、error 与 details 都不被压平。 */
  @Test
  void callCapabilityExecutesSuccessfully() throws Exception {
    fakeClient.result =
        McpToolCallResult.success(
            List.of(new TextResultContent("output text")), "{\"details\": 1}");
    McpLocalCallCapability capability = new McpLocalCallCapability(manager, executor);

    EnvironmentCapabilityResult result =
        execute(
            capability,
            "call-1",
            callJson("sayHello", "{\"name\":\"kk\"}", 10_000),
            Duration.ofSeconds(5));

    assertEquals("call-1", result.callId());
    assertFalse(result.error());
    assertEquals(1, result.contents().size());
    assertEquals("output text", ((TextResultContent) result.contents().getFirst()).text());
    assertEquals("{\"details\": 1}", result.detailsJson());
  }

  /** 连接/协议层失败返回固定不透明文本，绝不回显 command、cwd 或环境值。 */
  @Test
  void callCapabilityFailsWithOpaqueErrorOnException() throws Exception {
    fakeClient.throwOnCall = true;
    McpLocalCallCapability capability = new McpLocalCallCapability(manager, executor);

    EnvironmentCapabilityResult result =
        execute(capability, "call-2", callJson("badTool", "{}", 10_000), Duration.ofSeconds(5));

    assertTrue(result.error());
    assertEquals(
        "Error: mcp.local.call failed", ((TextResultContent) result.contents().getFirst()).text());
    assertFalse(result.contents().getFirst().toString().contains("/opt/test"));
  }

  /** 非法请求返回固定不透明文本，且不启动任何 MCP 进程。 */
  @Test
  void callCapabilityRejectsInvalidRequest() throws Exception {
    AtomicInteger created = new AtomicInteger();
    try (DaemonLocalMcpManager counting =
        new DaemonLocalMcpManager(
            (config, deadline) -> {
              created.incrementAndGet();
              return fakeClient;
            })) {
      McpLocalCallCapability capability = new McpLocalCallCapability(counting, executor);
      EnvironmentCapabilityResult result =
          execute(
              capability,
              "call-invalid",
              "{\"serverId\":\"bad\",\"configVersion\":1,\"toolName\":\"t\",\"arguments\":{},"
                  + "\"config\":{\"type\":\"local\",\"environmentId\":\"bad\",\"command\":[\"n\"],\"cwd\":\"/opt\"}}",
              Duration.ofSeconds(5));

      assertTrue(result.error());
      assertEquals(
          "Error: invalid mcp.local.call request",
          ((TextResultContent) result.contents().getFirst()).text());
      assertEquals(0, created.get(), "非法请求不得创建 MCP client");
    }
  }

  /** 取消必须立即给出单终态，并让在途调用以「取消」结束；取消后共享 client 不得被销毁。 */
  @Test
  void callCapabilityHandlesCancellation() throws Exception {
    McpLocalCallCapability capability = new McpLocalCallCapability(manager, executor);
    fakeClient.blockUntilCancelled = true;

    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-3", callJson("slowTool", "{}", 10_000)),
            Duration.ofSeconds(30));
    CompletableFuture<EnvironmentCapabilityResult> future = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle =
        capability.execute(
            request,
            new EnvironmentCapabilityExecutionListener() {
              @Override
              public void onComplete(EnvironmentCapabilityResult result) {
                future.complete(result);
              }

              @Override
              public void onError(Throwable error) {
                future.completeExceptionally(error);
              }
            });

    assertTrue(fakeClient.callStarted.await(5, TimeUnit.SECONDS), "在途调用必须已开始");
    handle.cancel();
    assertTrue(handle.isCancelled());

    EnvironmentCapabilityResult result = future.get(5, TimeUnit.SECONDS);
    assertTrue(result.error());
    assertTrue(((TextResultContent) result.contents().getFirst()).text().contains("cancelled"));
    // 取消是调用级事实：共享 client 保持可用
    assertFalse(fakeClient.closed, "取消不得销毁共享 client");
  }

  /** 请求预算与配置超时取较小值：请求预算更小时按请求预算超时。 */
  @Test
  void callCapabilityUsesSmallerOfRequestAndConfigBudget() throws Exception {
    McpLocalCallCapability capability = new McpLocalCallCapability(manager, executor);
    fakeClient.blockUntilCancelled = true;

    // 配置 30s、请求 300ms：必须按 300ms 结束，而不是等待配置的 30s
    EnvironmentCapabilityResult result =
        execute(
            capability, "call-budget", callJson("slowTool", "{}", 30_000), Duration.ofMillis(300));

    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().getFirst()).text().contains("timed out"),
        "请求预算更小时必须按请求预算超时");
  }

  /** 配置 timeoutMillis 小于请求预算时，按配置超时结束。 */
  @Test
  void callCapabilityHonoursConfigTimeoutWhenSmaller() throws Exception {
    McpLocalCallCapability capability = new McpLocalCallCapability(manager, executor);
    fakeClient.blockUntilCancelled = true;

    EnvironmentCapabilityResult result =
        execute(
            capability, "call-cfg-budget", callJson("slowTool", "{}", 300), Duration.ofSeconds(30));

    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().getFirst()).text().contains("timed out"),
        "配置超时更小时必须按配置超时结束");
  }

  /** discover 输出 identity envelope：{serverId, configVersion, tools[]}。 */
  @Test
  void discoverCapabilityReturnsIdentityEnvelope() throws Exception {
    fakeClient.tools =
        List.of(
            new McpToolDefinition("toolA", "Tool A description", "{\"type\":\"object\"}"),
            new McpToolDefinition("toolB", "Tool B description", "{}"));
    McpLocalDiscoverCapability capability = new McpLocalDiscoverCapability(manager, executor);

    EnvironmentCapabilityResult result =
        execute(capability, "disc-1", discoverJson(10_000), Duration.ofSeconds(5));

    assertFalse(result.error());
    JsonNode envelope =
        OBJECT_MAPPER.readTree(((JsonResultContent) result.contents().getFirst()).json());
    assertTrue(envelope.isObject(), "discover 结果必须是 JSON object envelope");
    assertEquals(SERVER_ID, envelope.get("serverId").asText());
    assertEquals(1, envelope.get("configVersion").asLong());
    assertTrue(envelope.get("tools").isArray());
    assertEquals(2, envelope.get("tools").size());
    assertEquals("toolA", envelope.get("tools").get(0).get("name").asText());
    assertEquals("Tool A description", envelope.get("tools").get(0).get("description").asText());
    assertTrue(envelope.get("tools").get(0).get("inputSchema").isObject());
    assertNotNull(envelope.get("tools").get(1).get("inputSchema"));
  }

  /** 服务端返回重名工具属于数据问题：返回固定错误文本，不销毁仍然健康的 client。 */
  @Test
  void discoverCapabilityRejectsDuplicateToolNames() throws Exception {
    fakeClient.tools =
        List.of(
            new McpToolDefinition("dup", "first", "{}"),
            new McpToolDefinition("dup", "second", "{}"));
    McpLocalDiscoverCapability capability = new McpLocalDiscoverCapability(manager, executor);

    EnvironmentCapabilityResult result =
        execute(capability, "disc-dup", discoverJson(10_000), Duration.ofSeconds(5));

    assertTrue(result.error());
    assertEquals(
        "Error: mcp.local.discover returned invalid tool list",
        ((TextResultContent) result.contents().getFirst()).text());
    assertFalse(fakeClient.closed, "数据问题不得销毁仍健康的 client");
  }

  /** discover 同样在协议层不可精确中止：取消必须立即结束本地等待并给出单终态。 */
  @Test
  void discoverCapabilityCancellationEndsLocalWait() throws Exception {
    fakeClient.blockListUntilCancelled = true;
    McpLocalDiscoverCapability capability = new McpLocalDiscoverCapability(manager, executor);

    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("disc-cancel", discoverJson(30_000)),
            Duration.ofSeconds(30));
    CompletableFuture<EnvironmentCapabilityResult> future = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle =
        capability.execute(
            request,
            new EnvironmentCapabilityExecutionListener() {
              @Override
              public void onComplete(EnvironmentCapabilityResult result) {
                future.complete(result);
              }

              @Override
              public void onError(Throwable error) {
                future.completeExceptionally(error);
              }
            });

    assertTrue(fakeClient.listStarted.await(5, TimeUnit.SECONDS));
    handle.cancel();

    EnvironmentCapabilityResult result = future.get(5, TimeUnit.SECONDS);
    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().getFirst()).text().contains("cancelled"),
        "discover 取消必须结束本地等待（SDK 不暴露 tools/list 的真实 request id，无法发送协议取消）");
  }

  /** 管理专用能力的 identity 与 catalog 一致。 */
  @Test
  void capabilitiesReportCatalogDescriptors() {
    assertEquals(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_CALL),
        new McpLocalCallCapability(manager, executor).descriptor());
    assertEquals(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER),
        new McpLocalDiscoverCapability(manager, executor).descriptor());
  }

  private static final class FakeClient implements McpClient {

    volatile McpToolCallResult result = McpToolCallResult.success(List.of(), "{}");
    volatile List<McpToolDefinition> tools = List.of();
    volatile boolean throwOnCall = false;
    volatile boolean blockUntilCancelled = false;
    volatile boolean blockListUntilCancelled = false;
    volatile boolean closed = false;
    final CountDownLatch callStarted = new CountDownLatch(1);
    final CountDownLatch listStarted = new CountDownLatch(1);
    final AtomicReference<McpCancellationToken> lastToken = new AtomicReference<>();

    @Override
    public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
      listStarted.countDown();
      lastToken.set(token);
      if (blockListUntilCancelled) {
        awaitCancellation(deadline, token);
      }
      return tools;
    }

    @Override
    public McpToolCallResult callTool(
        String toolName, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
      callStarted.countDown();
      lastToken.set(token);
      if (blockUntilCancelled) {
        awaitCancellation(deadline, token);
      }
      if (throwOnCall) {
        throw new McpException("tool failed");
      }
      return result;
    }

    /**
     * 模拟真实 client 的终止语义：阻塞直到「令牌被取消」或「deadline 过期」， 并分别以 {@link McpCancelledException} / {@link
     * McpTimeoutException} 结束，与生产实现一致。
     */
    private static void awaitCancellation(McpDeadline deadline, McpCancellationToken token) {
      long safetyLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < safetyLimit) {
        if (token.isCancelled()) {
          throw new McpCancelledException("MCP operation cancelled by caller");
        }
        if (deadline.isExpired()) {
          throw new McpTimeoutException("MCP operation timed out");
        }
        try {
          Thread.sleep(5);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          if (token.isCancelled()) {
            throw new McpCancelledException("MCP operation cancelled by caller");
          }
          throw new McpException("interrupted");
        }
      }
      throw new McpTimeoutException("MCP operation timed out");
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
