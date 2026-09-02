package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 单个 MCP 工具的可执行 SPI 测试。
 *
 * <p>验证 requirements、descriptor 契约、异步执行与取消能力。
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
    McpExecutableTool tool =
        new McpExecutableTool(
            descriptor, new McpExecutableTool.SourceName("echo"), connection, clientFactory);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertFalse(tool.requirements().environmentRequired());
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
    McpExecutableTool tool =
        new McpExecutableTool(
            descriptor, new McpExecutableTool.SourceName("echo"), connection, clientFactory);

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
  void cancelHandleMarksCancelled() {
    // 意图：验证取消 Handle 标记 isCancelled 并清理
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
    McpExecutableTool tool =
        new McpExecutableTool(
            descriptor, new McpExecutableTool.SourceName("echo"), connection, clientFactory);

    ToolCall call = new ToolCall("call_2", "mcp_srv_echo", "{}");
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(descriptor, call, Duration.ofSeconds(5)),
            new ToolExecutionListener() {
              @Override
              public void onPartial(ToolResult partial) {}

              @Override
              public void onComplete(ToolOutcome outcome) {}

              @Override
              public void onError(Throwable error) {}
            });

    handle.cancel();
    assertTrue(handle.isCancelled());
  }
}
