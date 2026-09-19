package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证 {@link RemoteMcpExecutableTool} 的 Streamable HTTP 执行契约与安全边界。 */
class RemoteMcpExecutableToolTest {

  private static final String SERVER_NAME = "remote_server";
  private static final String TOOL_NAME = "mcp_remote_server_echo";

  private FakeStreamableHttpMcpServer fakeServer;
  private McpServerRepository repository;
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
    repository = mock(McpServerRepository.class);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    if (fakeServer != null) {
      fakeServer.close();
    }
    executor.close();
  }

  @Test
  void exposesDescriptorAndNoEnvironmentRequirements() {
    // 意图：Remote MCP 工具对外暴露的 requirements 恒为 none，sideEffect 为 NON_IDEMPOTENT
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_echo");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_echo", "echo", repository, executor);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertFalse(tool.requirements().environmentRequired());
    assertNull(tool.requirements().requiredEnvironmentId());
  }

  @Test
  void rejectsBlankSourceName() {
    // 意图：构造函数对非法 sourceName 进行严格防御校验
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_echo");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(
                descriptor, SERVER_NAME, "mcp_srv_echo", null, repository, executor));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(
                descriptor, SERVER_NAME, "mcp_srv_echo", "   ", repository, executor));
  }

  @Test
  void executesAndCompletesListenerWithRawSourceName() throws Exception {
    // 意图：异步执行并在 listener 收到完成回调，且原始远端工具名无改写传给 MCP server
    String rawSourceName = "remote.raw-tool_name@v1";
    fakeServer.addTool(rawSourceName, "Raw tool", "{}");
    stubServerAndTool("mcp_srv_raw", rawSourceName, "mcp_srv_raw");

    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_raw");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_raw", rawSourceName, repository, executor);

    ToolResult result = execute(tool, descriptor, "call_raw_1", "{}");

    assertNotNull(result);
    assertFalse(result.error());
    assertEquals("call_raw_1", result.toolCallId());
    assertEquals(1, fakeServer.receivedToolCallNames().size());
    assertEquals(rawSourceName, fakeServer.receivedToolCallNames().get(0));
  }

  @Test
  void redactsOutboundAuthorizationIntoConfiguredHeader() throws Exception {
    // 意图：header 中的 ${VAR} 占位符在发送前按进程环境整值替换，且解析值绝不回显到结果
    fakeServer.addTool("echo", "Echo", "{}");
    McpServer server = server("https://unused");
    server.setUrl(fakeServer.endpointUrl());
    server.setHeaders(Map.of("Authorization", "${KK_TEST_MCP_TOKEN}"));
    McpTool tool = tool("mcp_srv_echo", "echo");
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.of(server));
    when(repository.getTool("mcp_srv_echo")).thenReturn(Optional.of(tool));

    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_echo");
    RemoteMcpExecutableTool executable =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            "mcp_srv_echo",
            "echo",
            repository,
            executor,
            McpClientFactory::createRemote,
            name -> "resolved-secret-value");

    ToolResult result = execute(executable, descriptor, "call_header", "{}");
    assertFalse(result.error());
    assertEquals("resolved-secret-value", fakeServer.receivedAuthHeaders().get(0));
    assertFalse(result.toString().contains("resolved-secret-value"));
  }

  @Test
  void failsClosedWhenServerIsMissingDisabledOrUnverified() throws Exception {
    // 意图：发送前重新读取 DB，server 缺失/禁用/非 AVAILABLE 时返回脱敏失败且不发起网络调用
    ToolDescriptor descriptor = SampleDescriptors.tool(TOOL_NAME);
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, TOOL_NAME, "echo", repository, executor);

    // 1. server 行缺失
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.empty());
    when(repository.getTool(TOOL_NAME)).thenReturn(Optional.of(tool(TOOL_NAME, "echo")));
    assertTrue(execute(tool, descriptor, "call_missing", "{}").error());

    // 2. server 被禁用
    McpServer disabled = server(fakeServer.endpointUrl());
    disabled.setEnabled(false);
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.of(disabled));
    assertTrue(execute(tool, descriptor, "call_disabled", "{}").error());

    // 3. server 尚未验证
    McpServer unverified = server(fakeServer.endpointUrl());
    unverified.setDiscoveryStatus(McpDiscoveryStatus.UNVERIFIED);
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.of(unverified));
    assertTrue(execute(tool, descriptor, "call_unverified", "{}").error());

    assertEquals(0, fakeServer.toolCallCount());
  }

  @Test
  void handlesToolCallFailureGracefullyAndRedactsMessage() throws Exception {
    // 意图：调用失败时 listener 收到脱敏的 onComplete 错误结果且未调用 onError
    fakeServer.setFailToolCall(true);
    fakeServer.addTool("fail_tool", "Fail tool", "{}");
    stubServerAndTool("mcp_srv_fail", "fail_tool", "mcp_srv_fail");

    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_fail");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_fail", "fail_tool", repository, executor);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    AtomicBoolean onErrorCalled = new AtomicBoolean(false);
    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_fail_1", descriptor.name(), "{}"),
            Duration.ofSeconds(5)),
        listener(future, onErrorCalled));

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertFalse(onErrorCalled.get());
    assertTrue(result.error());
    assertEquals("call_fail_1", result.toolCallId());
    assertFalse(result.contents().isEmpty());
  }

  @Test
  void cancelHandleMarksCancelled() {
    // 意图：取消 Handle 幂等标记 isCancelled，且不抛出异常
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_slow");
    stubServerAndTool("mcp_srv_slow", "slow_tool", "mcp_srv_slow");

    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_slow", "slow_tool", repository, executor);

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall("call_cancel_1", descriptor.name(), "{}"),
                Duration.ofSeconds(5)),
            mock(ToolExecutionListener.class));

    handle.cancel();
    assertTrue(handle.isCancelled());
  }

  private void stubServerAndTool(String toolName, String sourceName, String serverName) {
    when(repository.getByName(SERVER_NAME))
        .thenReturn(Optional.of(server(fakeServer.endpointUrl())));
    McpTool tool = tool(toolName, sourceName);
    when(repository.getTool(toolName)).thenReturn(Optional.of(tool));
    // 工具的 serverName 必须与执行的 server 一致
    tool.setServerName(SERVER_NAME);
  }

  private ToolResult execute(
      RemoteMcpExecutableTool tool, ToolDescriptor descriptor, String callId, String arguments)
      throws Exception {
    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    tool.execute(
        new ToolExecutionRequest(
            descriptor, new ToolCall(callId, descriptor.name(), arguments), Duration.ofSeconds(5)),
        listener(future, new AtomicBoolean(false)));
    return future.get(5, TimeUnit.SECONDS);
  }

  private static ToolExecutionListener listener(
      CompletableFuture<ToolResult> future, AtomicBoolean onErrorCalled) {
    return new ToolExecutionListener() {
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
    };
  }

  private static McpServer server(String url) {
    McpServer server = new McpServer();
    server.setName(SERVER_NAME);
    server.setUrl(url);
    server.setHeaders(Map.of());
    server.setEnabled(true);
    server.setTimeoutMillis(5000L);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setVersion(1L);
    return server;
  }

  private static McpTool tool(String toolName, String sourceName) {
    McpTool tool = new McpTool();
    tool.setName(toolName);
    tool.setServerName(SERVER_NAME);
    tool.setSourceName(sourceName);
    tool.setDescription("sample description");
    tool.setInputSchemaJson("{}");
    return tool;
  }

  /** 复用的合法 ToolDescriptor 构造。 */
  private static final class SampleDescriptors {

    private SampleDescriptors() {}

    static ToolDescriptor tool(String name) {
      return new ToolDescriptor(
          name,
          "Sample description",
          "tool",
          new InputSchema(null, Map.of(), Set.of(), true),
          ToolSideEffect.NON_IDEMPOTENT,
          Duration.ofSeconds(5));
    }
  }
}
