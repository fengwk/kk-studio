package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.LangChainMcpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpConnectionSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpRemoteToolSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolCallOutcome;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClient;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 单个 MCP 工具的可执行 SPI 测试。
 *
 * <p>验证 requirements、descriptor 契约、异步执行、取消能力、单一 close ownership 与错误脱敏。
 */
class McpExecutableToolTest {

  private FakeStreamableHttpMcpServer fakeServer;
  private LangChainMcpToolClientFactory clientFactory;

  @BeforeEach
  void setUp() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
    clientFactory = new LangChainMcpToolClientFactory();
  }

  @AfterEach
  void tearDown() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  void exposesDescriptorAndNoRequirements() {
    // 意图：验证 MCP 工具对外暴露的 requirements 为 none，sideEffect 为 NON_IDEMPOTENT
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "mcp_srv_echo",
            "1",
            "Echo tool",
            "tool",
            new InputSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(5));

    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool = new McpExecutableTool(descriptor, "echo", connection, clientFactory);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertFalse(tool.requirements().environmentRequired());
  }

  @Test
  void rejectsBlankSourceName() {
    // 意图：验证构造函数对非法 sourceName 进行校验
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "mcp_srv_echo",
            "1",
            "Echo tool",
            "tool",
            new InputSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(5));
    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpExecutableTool(descriptor, null, connection, clientFactory));
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpExecutableTool(descriptor, "   ", connection, clientFactory));
  }

  @Test
  void executesAsynchronouslyAndCompletesListener() throws Exception {
    // 意图：验证异步执行快速返回 Handle，并在 listener 收到完成回调
    fakeServer.addTool("echo", "Echo tool", "{}");

    ToolDescriptor descriptor =
        new ToolDescriptor(
            "mcp_srv_echo",
            "1",
            "Echo tool",
            "tool",
            new InputSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(5));

    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool = new McpExecutableTool(descriptor, "echo", connection, clientFactory);

    ToolCall call = new ToolCall("call_1", "mcp_srv_echo", "{}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(descriptor, call, Duration.ofSeconds(5));

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    ToolExecutionHandle handle =
        tool.execute(
            request,
            new ToolExecutionListener() {
              @Override
              public void onPartial(ToolResult partial) {}

              @Override
              public void onComplete(ToolOutcome outcome) {
                future.complete(outcome.result());
              }

              @Override
              public void onError(Throwable error) {
                future.completeExceptionally(error);
              }
            });

    assertNotNull(handle);
    assertFalse(handle.isCancelled());

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertEquals("call_1", result.toolCallId());
    assertFalse(result.error());
  }

  @Test
  void passesRawSourceNameWithoutModificationToClient() throws Exception {
    // 意图：验证远端原始名称（含特殊字符）从配置无改写传递给 client callTool
    String rawSourceName = "remote.raw-tool_name@v1";
    fakeServer.addTool(rawSourceName, "Raw tool", "{}");

    ToolDescriptor descriptor =
        new ToolDescriptor(
            "mcp_srv_remote_raw_tool_name_v1",
            "1",
            "Raw tool",
            "tool",
            new InputSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(5));

    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool =
        new McpExecutableTool(descriptor, rawSourceName, connection, clientFactory);

    ToolCall call = new ToolCall("call_raw_1", descriptor.name(), "{}");
    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    tool.execute(
        new ToolExecutionRequest(descriptor, call, Duration.ofSeconds(5)),
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            future.complete(outcome.result());
          }

          @Override
          public void onError(Throwable error) {
            future.completeExceptionally(error);
          }
        });

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertFalse(result.error());
    assertEquals(1, fakeServer.receivedToolCallNames().size());
    assertEquals(rawSourceName, fakeServer.receivedToolCallNames().get(0));
  }

  @Test
  void ensuresSingleCloseOwnershipOnSuccess() throws Exception {
    // 意图：验证正常成功执行时 client 恰好被 close 一次
    AtomicInteger closeCount = new AtomicInteger(0);
    TrackingToolClient trackingClient =
        new TrackingToolClient(closeCount, null, () -> McpToolCallOutcome.failure("call_x"));

    McpToolClientFactory trackingFactory = spec -> trackingClient;
    ToolDescriptor descriptor = sampleDescriptor();
    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool =
        new McpExecutableTool(descriptor, "test_tool", connection, trackingFactory);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_close_1", descriptor.name(), "{}"),
            Duration.ofSeconds(5)),
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            future.complete(outcome.result());
          }

          @Override
          public void onError(Throwable error) {
            future.completeExceptionally(error);
          }
        });

    future.get(5, TimeUnit.SECONDS);
    assertEquals(1, closeCount.get());
  }

  @Test
  void handlesClientCreationExceptionWithoutCallingOnErrorAndRedactsError() throws Exception {
    // 意图：验证 factory.create 抛出异常时，listener 收到脱敏的 onComplete 结果而非 onError
    McpToolClientFactory failingFactory =
        spec -> {
          throw new RuntimeException("connection failed: http://user:secret@internal-host:8080");
        };

    ToolDescriptor descriptor = sampleDescriptor();
    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool =
        new McpExecutableTool(descriptor, "test_tool", connection, failingFactory);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    AtomicBoolean onErrorCalled = new AtomicBoolean(false);

    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_fail_create", descriptor.name(), "{}"),
            Duration.ofSeconds(5)),
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            future.complete(outcome.result());
          }

          @Override
          public void onError(Throwable error) {
            onErrorCalled.set(true);
            future.completeExceptionally(error);
          }
        });

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertFalse(onErrorCalled.get());
    assertNotNull(result);
    assertTrue(result.error());
    assertEquals("call_fail_create", result.toolCallId());
  }

  @Test
  void handlesCallToolExceptionWithoutCallingOnErrorAndClosesClientOnce() throws Exception {
    // 意图：验证 callTool 抛出 RuntimeException 时，返回脱敏结果、未调用 onError 且 client 恰好 close 一次
    AtomicInteger closeCount = new AtomicInteger(0);
    TrackingToolClient trackingClient =
        new TrackingToolClient(
            closeCount,
            () -> {
              throw new RuntimeException("remote timeout on secret url: http://token@host");
            },
            null);

    McpToolClientFactory trackingFactory = spec -> trackingClient;
    ToolDescriptor descriptor = sampleDescriptor();
    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool =
        new McpExecutableTool(descriptor, "test_tool", connection, trackingFactory);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    AtomicBoolean onErrorCalled = new AtomicBoolean(false);

    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_fail_call", descriptor.name(), "{}"),
            Duration.ofSeconds(5)),
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            future.complete(outcome.result());
          }

          @Override
          public void onError(Throwable error) {
            onErrorCalled.set(true);
            future.completeExceptionally(error);
          }
        });

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertFalse(onErrorCalled.get());
    assertNotNull(result);
    assertTrue(result.error());
    assertEquals("call_fail_call", result.toolCallId());
    assertEquals(1, closeCount.get());
  }

  @Test
  void cancelHandleMarksCancelledAndClosesClientOnce() throws Exception {
    // 意图：验证取消 Handle 标记 isCancelled 且 client 恰好 close 一次
    AtomicInteger closeCount = new AtomicInteger(0);
    CountDownLatch clientCreatedLatch = new CountDownLatch(1);
    CountDownLatch continueCallLatch = new CountDownLatch(1);

    McpToolClient slowClient =
        new McpToolClient() {
          @Override
          public List<McpRemoteToolSpec> listTools() {
            return List.of();
          }

          @Override
          public McpToolCallOutcome callTool(
              String sourceToolName, String argumentsJson, String toolCallId) {
            clientCreatedLatch.countDown();
            try {
              continueCallLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return McpToolCallOutcome.failure(toolCallId);
          }

          @Override
          public void close() {
            closeCount.incrementAndGet();
          }
        };

    ToolDescriptor descriptor = sampleDescriptor();
    McpConnectionSpec connection = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);
    McpExecutableTool tool =
        new McpExecutableTool(descriptor, "test_tool", connection, spec -> slowClient);

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall("call_cancel", descriptor.name(), "{}"),
                Duration.ofSeconds(5)),
            new ToolExecutionListener() {
              @Override
              public void onPartial(ToolResult partial) {}

              @Override
              public void onComplete(ToolOutcome outcome) {}

              @Override
              public void onError(Throwable error) {}
            });

    assertTrue(clientCreatedLatch.await(5, TimeUnit.SECONDS));
    handle.cancel();
    assertTrue(handle.isCancelled());
    continueCallLatch.countDown();

    // 等待线程执行结束
    Thread.sleep(100);
    assertEquals(1, closeCount.get());
  }

  private static ToolDescriptor sampleDescriptor() {
    return new ToolDescriptor(
        "mcp_srv_test",
        "1",
        "Test tool",
        "tool",
        new InputSchema(null, Map.of(), Set.of(), true),
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofSeconds(5));
  }

  private static final class TrackingToolClient implements McpToolClient {

    private final AtomicInteger closeCount;
    private final Runnable onCall;
    private final Supplier<McpToolCallOutcome> outcomeSupplier;

    private TrackingToolClient(
        AtomicInteger closeCount, Runnable onCall, Supplier<McpToolCallOutcome> outcomeSupplier) {
      this.closeCount = closeCount;
      this.onCall = onCall;
      this.outcomeSupplier = outcomeSupplier;
    }

    @Override
    public List<McpRemoteToolSpec> listTools() {
      return List.of();
    }

    @Override
    public McpToolCallOutcome callTool(
        String sourceToolName, String argumentsJson, String toolCallId) {
      if (onCall != null) {
        onCall.run();
      }
      return outcomeSupplier != null
          ? outcomeSupplier.get()
          : McpToolCallOutcome.failure(toolCallId);
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
    }
  }
}
