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

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpException;
import fun.fengwk.kkstudio.harness.mcp.McpTimeoutException;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 {@link RemoteMcpExecutableTool} 的 Streamable HTTP 执行契约与安全边界。 */
class RemoteMcpExecutableToolTest {

  private static final String SERVER_NAME = "remote_server";
  private static final String TOOL_NAME = "mcp_remote_server_echo";

  private FakeStreamableHttpMcpServer fakeServer;
  private McpServerRepository repository;

  @BeforeEach
  void setUp() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
    repository = mock(McpServerRepository.class);
  }

  @AfterEach
  void tearDown() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  void exposesDescriptorAndNoEnvironmentRequirements() {
    // 意图：Remote MCP 工具对外暴露的 requirements 恒为 none，sideEffect 为 NON_IDEMPOTENT
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_echo");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(descriptor, SERVER_NAME, "mcp_srv_echo", "echo", repository);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertEquals(EnvironmentSupport.NONE, tool.requirements().environmentSupport());
    assertNull(tool.requirements().requiredEnvironmentId());
  }

  @Test
  void rejectsBlankSourceName() {
    // 意图：构造函数对非法 sourceName 进行严格防御校验
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_echo");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(descriptor, SERVER_NAME, "mcp_srv_echo", null, repository));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(
                descriptor, SERVER_NAME, "mcp_srv_echo", "   ", repository));
  }

  @Test
  void executesAndReturnsResultWithRawSourceName() throws Exception {
    // 意图：阻塞执行并返回结果，且原始远端工具名无改写传给 MCP server
    String rawSourceName = "remote.raw-tool_name@v1";
    fakeServer.addTool(rawSourceName, "Raw tool", "{}");
    stubServerAndTool("mcp_srv_raw", rawSourceName, "mcp_srv_raw");

    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_raw");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_raw", rawSourceName, repository);

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
            McpClientFactory::createRemote,
            name -> "resolved-secret-value");

    ToolResult result = execute(executable, descriptor, "call_header", "{}");
    assertFalse(result.error());
    assertEquals("resolved-secret-value", fakeServer.receivedAuthHeaders().get(0));
    assertFalse(result.toString().contains("resolved-secret-value"));
  }

  @Test
  void failsClosedAtInvocationWhenServerOrToolIsMissingDisabledOrUnavailable() throws Exception {
    // 意图：发送前重新读取 DB，server 缺失/禁用/非 AVAILABLE 时返回脱敏失败且不发起网络调用。
    // 这里同时覆盖目录查找不再按可用性过滤后的反例：即使 tool 行已由 McpToolCatalog.findTool 解析出来，
    // 调用期仍必须按当前 DB 行 fail closed。
    ToolDescriptor descriptor = SampleDescriptors.tool(TOOL_NAME);
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(descriptor, SERVER_NAME, TOOL_NAME, "echo", repository);

    // 1. server 行缺失
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.empty());
    when(repository.getTool(TOOL_NAME)).thenReturn(Optional.of(tool(TOOL_NAME, "echo")));
    ToolResult missingServer = execute(tool, descriptor, "call_missing", "{}");
    assertTrue(missingServer.error());
    // 失败必须是本次调用自己的终态结果：call id 对齐且文本脱敏，绝不泄漏 endpoint；后续 2-5 走同一分支。
    assertEquals("call_missing", missingServer.toolCallId());
    assertFalse(missingServer.toString().contains(fakeServer.endpointUrl()));

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

    // 4. server 发现失败
    McpServer failed = server(fakeServer.endpointUrl());
    failed.setDiscoveryStatus(McpDiscoveryStatus.FAILED);
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.of(failed));
    assertTrue(execute(tool, descriptor, "call_failed", "{}").error());

    // 5. 工具行缺失
    when(repository.getByName(SERVER_NAME))
        .thenReturn(Optional.of(server(fakeServer.endpointUrl())));
    when(repository.getTool(TOOL_NAME)).thenReturn(Optional.empty());
    assertTrue(execute(tool, descriptor, "call_tool_missing", "{}").error());

    // 不可用只终结该次调用：整个失败序列不得产生任何网络 I/O（连握手都不允许），
    // 因此用请求总数断言，而不只是工具调用次数。
    assertEquals(0, fakeServer.toolCallCount());
    assertEquals(0, fakeServer.requestCount());

    // 反向对照：同一进程内健康 server 的兄弟工具照常发送并成功，证明不可用性不扩散到其他工具。
    String siblingServerName = "healthy_server";
    String siblingToolName = "mcp_healthy_server_echo";
    fakeServer.addTool("echo", "Echo", "{}");
    when(repository.getByName(siblingServerName))
        .thenReturn(Optional.of(serverOf(siblingServerName, fakeServer.endpointUrl())));
    McpTool siblingRow = tool(siblingToolName, "echo");
    siblingRow.setServerName(siblingServerName);
    when(repository.getTool(siblingToolName)).thenReturn(Optional.of(siblingRow));
    ToolDescriptor siblingDescriptor = SampleDescriptors.tool(siblingToolName);
    RemoteMcpExecutableTool sibling =
        new RemoteMcpExecutableTool(
            siblingDescriptor, siblingServerName, siblingToolName, "echo", repository);

    ToolResult siblingResult = execute(sibling, siblingDescriptor, "call_sibling", "{}");
    assertFalse(siblingResult.error());
    assertEquals("call_sibling", siblingResult.toolCallId());
    assertEquals(1, fakeServer.toolCallCount());
    // 健康调用确实产生了网络 I/O：证明上面的 requestCount==0 是「没有请求」而不是计数器失效。
    assertTrue(fakeServer.requestCount() > 0);
  }

  @Test
  void handlesToolCallFailureGracefullyAndRedactsMessage() throws Exception {
    // 意图：调用失败时返回脱敏的错误结果而不是抛出
    fakeServer.setFailToolCall(true);
    fakeServer.addTool("fail_tool", "Fail tool", "{}");
    stubServerAndTool("mcp_srv_fail", "fail_tool", "mcp_srv_fail");

    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_fail");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, SERVER_NAME, "mcp_srv_fail", "fail_tool", repository);

    ToolResult result = execute(tool, descriptor, "call_fail_1", "{}");
    assertTrue(result.error());
    assertEquals("call_fail_1", result.toolCallId());
    assertFalse(result.contents().isEmpty());
  }

  /** 线程中断即取消：令牌被取消、client 被关闭，并以 InterruptedException 结束，不产生任何结果。 */
  @Test
  void interruptionCancelsTheCallAndClosesTheClient() throws Exception {
    stubServerAndTool("mcp_srv_slow", "slow_tool", "mcp_srv_slow");
    CountDownLatch inCall = new CountDownLatch(1);
    AtomicReference<McpCancellationToken> seenToken = new AtomicReference<>();
    AtomicBoolean clientClosed = new AtomicBoolean(false);
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_slow");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            "mcp_srv_slow",
            "slow_tool",
            repository,
            (config, deadline) ->
                new McpClient() {
                  @Override
                  public List<McpToolDefinition> listTools(
                      McpDeadline deadline, McpCancellationToken token) {
                    throw new UnsupportedOperationException();
                  }

                  @Override
                  public McpToolCallResult callTool(
                      String name,
                      String argumentsJson,
                      McpDeadline deadline,
                      McpCancellationToken token) {
                    seenToken.set(token);
                    inCall.countDown();
                    try {
                      Thread.sleep(60_000);
                    } catch (InterruptedException interrupted) {
                      // 与真实 client 一致：恢复中断标志并以失败异常结束。
                      Thread.currentThread().interrupt();
                      throw new McpException("interrupted");
                    }
                    throw new AssertionError("must be interrupted");
                  }

                  @Override
                  public void close() {
                    clientClosed.set(true);
                  }
                },
            name -> null);
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    tool.execute(
                        new ToolExecutionRequest(
                            descriptor,
                            new ToolCall("call_cancel_1", descriptor.name(), "{}"),
                            Duration.ofSeconds(5)),
                        partial -> {});
                  } catch (Throwable failure) {
                    outcome.set(failure);
                  }
                });
    assertTrue(inCall.await(5, TimeUnit.SECONDS));

    worker.interrupt();
    worker.join(5000);

    assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
    assertTrue(seenToken.get().isCancelled());
    assertTrue(clientClosed.get());
  }

  /** 调用超时是本次调用的终态：返回脱敏超时结果，客户端仍被关闭，内部细节绝不外泄。 */
  @Test
  void reportsTimeoutAsRedactedErrorResultAndClosesClient() throws Exception {
    // 意图：McpTimeoutException 映射为固定超时文案的错误结果，且 try-with-resources 仍关闭客户端
    stubServerAndTool("mcp_srv_slow", "slow_tool", "mcp_srv_slow");
    AtomicBoolean callToolInvoked = new AtomicBoolean(false);
    AtomicBoolean clientClosed = new AtomicBoolean(false);
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_slow");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            "mcp_srv_slow",
            "slow_tool",
            repository,
            (config, deadline) ->
                throwingClient(
                    () -> {
                      callToolInvoked.set(true);
                      throw new McpTimeoutException("internal-timeout-marker");
                    },
                    () -> clientClosed.set(true)),
            name -> null);

    ToolResult result = execute(tool, descriptor, "call_timeout", "{}");

    assertTrue(callToolInvoked.get());
    assertTrue(clientClosed.get());
    assertTrue(result.error());
    assertEquals("call_timeout", result.toolCallId());
    assertEquals("MCP tool call timed out", firstText(result));
    assertFalse(result.toString().contains("internal-timeout-marker"));
  }

  /** 非超时/取消的意外失败同样终结为脱敏失败结果，且不把原始异常文本回显给模型。 */
  @Test
  void reportsUnexpectedFailureAsRedactedErrorResultAndClosesClient() throws Exception {
    // 意图：任意 RuntimeException 只以固定失败文案结案，底层异常文本不跨越模块边界
    stubServerAndTool("mcp_srv_broken", "broken_tool", "mcp_srv_broken");
    AtomicBoolean clientClosed = new AtomicBoolean(false);
    ToolDescriptor descriptor = SampleDescriptors.tool("mcp_srv_broken");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            "mcp_srv_broken",
            "broken_tool",
            repository,
            (config, deadline) ->
                throwingClient(
                    () -> {
                      throw new IllegalStateException("internal-failure-marker");
                    },
                    () -> clientClosed.set(true)),
            name -> null);

    ToolResult result = execute(tool, descriptor, "call_broken", "{}");

    assertTrue(clientClosed.get());
    assertTrue(result.error());
    assertEquals("call_broken", result.toolCallId());
    assertEquals("MCP tool call failed", firstText(result));
    assertFalse(result.toString().contains("internal-failure-marker"));
  }

  /** 只实现执行路径的 client 替身：callTool 主体按用例抛错，close 用于验证资源释放。 */
  private static McpClient throwingClient(Runnable callToolBody, Runnable onClose) {
    return new McpClient() {
      @Override
      public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
        throw new UnsupportedOperationException("callTool path does not list tools");
      }

      @Override
      public McpToolCallResult callTool(
          String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
        callToolBody.run();
        throw new AssertionError("callTool body must terminate by throwing");
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  private static String firstText(ToolResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
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
    return tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall(callId, descriptor.name(), arguments),
                Duration.ofSeconds(5)),
            partial -> {})
        .result();
  }

  private static McpServer server(String url) {
    return serverOf(SERVER_NAME, url);
  }

  private static McpServer serverOf(String name, String url) {
    McpServer server = new McpServer();
    server.setName(name);
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
