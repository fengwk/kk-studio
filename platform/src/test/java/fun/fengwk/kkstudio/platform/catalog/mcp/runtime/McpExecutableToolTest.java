package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证 {@link RemoteMcpExecutableTool} 与 {@link LocalMcpExecutableTool} 的执行契约与安全边界。 */
class McpExecutableToolTest {

  private FakeStreamableHttpMcpServer fakeServer;
  private McpServerRepository repository;
  private ExecutorService executor;
  private final UUID serverId = UUID.randomUUID();
  private final UUID toolId = UUID.randomUUID();
  private final UUID envUuid = UUID.randomUUID();

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
  void remoteToolExposesDescriptorAndNoRequirements() {
    // 意图：验证 Remote MCP 工具对外暴露的 requirements 为 none，sideEffect 为 NON_IDEMPOTENT
    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_echo");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, "echo", 1L, 1L, repository, executor);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertFalse(tool.requirements().environmentRequired());
    assertNull(tool.requirements().requiredEnvironmentId());
  }

  @Test
  void remoteToolRejectsBlankSourceName() {
    // 意图：验证 Remote 构造函数对非法 sourceName 进行严格防御校验
    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_echo");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(
                descriptor, serverId, toolId, null, 1L, 1L, repository, executor));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteMcpExecutableTool(
                descriptor, serverId, toolId, "   ", 1L, 1L, repository, executor));
  }

  @Test
  void remoteToolExecutesAsynchronouslyAndCompletesListener() throws Exception {
    // 意图：验证 Remote 工具异步执行并在 listener 收到完成回调
    fakeServer.addTool("echo", "Echo tool", "{}");

    McpServer server = createRemoteServer();
    McpTool mcpTool = createTool("echo", "mcp_srv_echo");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_echo");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, "echo", 1L, 1L, repository, executor);

    ToolCall call = new ToolCall("call_1", "mcp_srv_echo", "{\"message\":\"hello\"}");
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
  void remoteToolPassesRawSourceNameWithoutModificationToClient() throws Exception {
    // 意图：验证远端原始工具名无改写传递给远端
    String rawSourceName = "remote.raw-tool_name@v1";
    fakeServer.addTool(rawSourceName, "Raw tool", "{}");

    McpServer server = createRemoteServer();
    McpTool mcpTool = createTool(rawSourceName, "mcp_srv_raw");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_raw");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, rawSourceName, 1L, 1L, repository, executor);

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
  void remoteToolHandlesErrorGracefullyAndRedactsMessage() throws Exception {
    // 意图：验证调用失败时 listener 收到脱敏的 onComplete 错误结果且未调用 onError
    fakeServer.setFailToolCall(true);
    fakeServer.addTool("fail_tool", "Fail tool", "{}");

    McpServer server = createRemoteServer();
    McpTool mcpTool = createTool("fail_tool", "mcp_srv_fail");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_fail");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, "fail_tool", 1L, 1L, repository, executor);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    AtomicBoolean onErrorCalled = new AtomicBoolean(false);

    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_fail_1", descriptor.name(), "{}"),
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
    assertEquals("call_fail_1", result.toolCallId());
    assertFalse(result.contents().isEmpty());
  }

  @Test
  void remoteToolCompletesListenerWhenPersistedConfigurationCannotBeDecoded() throws Exception {
    // 意图：验证配置解码异常也被异步执行边界收敛，避免返回永不终结的执行句柄
    McpServer server = createRemoteServer();
    server.setConnectionConfig("{\"url\":\"sensitive-marker\"");
    McpTool mcpTool = createTool("echo", "mcp_srv_echo");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_echo");
    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, "echo", 1L, 1L, repository, executor);
    CompletableFuture<ToolResult> future = new CompletableFuture<>();

    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_bad_config", descriptor.name(), "{}"),
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

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertTrue(result.error());
    assertFalse(result.toString().contains("sensitive-marker"));
  }

  @Test
  void remoteToolCancelHandleMarksCancelled() {
    // 意图：验证取消 Handle 标记 isCancelled
    ToolDescriptor descriptor = sampleDescriptor("mcp_srv_slow");
    McpServer server = createRemoteServer();
    McpTool mcpTool = createTool("slow_tool", "mcp_srv_slow");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor, serverId, toolId, "slow_tool", 1L, 1L, repository, executor);

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

  @Test
  void localToolExposesRequirementsWithEnvironmentId() {
    // 意图：验证 Local MCP 工具对外暴露正确的 requiredEnvironmentId
    ToolDescriptor descriptor = sampleDescriptor("mcp_local_tool");
    LocalMcpExecutableTool tool =
        new LocalMcpExecutableTool(
            descriptor, serverId, toolId, "tool", envUuid, 1L, 1L, repository);

    assertEquals(descriptor, tool.descriptor());
    assertNotNull(tool.requirements());
    assertTrue(tool.requirements().environmentRequired());
    assertEquals(EnvironmentId.of(envUuid), tool.requirements().requiredEnvironmentId());
  }

  @Test
  void localToolRejectsBlankSourceName() {
    // 意图：验证 Local 构造函数对非法 sourceName 进行严格防御校验
    ToolDescriptor descriptor = sampleDescriptor("mcp_local_tool");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LocalMcpExecutableTool(
                descriptor, serverId, toolId, null, envUuid, 1L, 1L, repository));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LocalMcpExecutableTool(
                descriptor, serverId, toolId, "   ", envUuid, 1L, 1L, repository));
  }

  @Test
  void localToolFailsWhenBoundEnvironmentIsMissing() throws Exception {
    // 意图：验证执行请求未附带 BoundEnvironment 时优雅失败
    ToolDescriptor descriptor = sampleDescriptor("mcp_local_tool");
    McpServer server = createLocalServer();
    McpTool mcpTool = createTool("tool", "mcp_local_tool");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    LocalMcpExecutableTool tool =
        new LocalMcpExecutableTool(
            descriptor, serverId, toolId, "tool", envUuid, 1L, 1L, repository);

    CompletableFuture<ToolResult> future = new CompletableFuture<>();
    tool.execute(
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call_no_env", descriptor.name(), "{}"),
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

    ToolResult result = future.get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertTrue(result.error());
    assertEquals("call_no_env", result.toolCallId());
  }

  @Test
  void localToolDispatchesToBoundEnvironment() throws Exception {
    // 意图：验证 Local 工具将调用正确包装为 mcp.local.call capability 派发给 BoundEnvironment
    ToolDescriptor descriptor = sampleDescriptor("mcp_local_tool");
    McpServer server = createLocalServer();
    McpTool mcpTool = createTool("my_tool", "mcp_local_tool");
    when(repository.getById(serverId)).thenReturn(Optional.of(server));
    when(repository.getToolById(toolId)).thenReturn(Optional.of(mcpTool));

    LocalMcpExecutableTool tool =
        new LocalMcpExecutableTool(
            descriptor, serverId, toolId, "my_tool", envUuid, 1L, 1L, repository);

    BoundEnvironment boundEnv = mock(BoundEnvironment.class);
    when(boundEnv.environmentId()).thenReturn(EnvironmentId.of(envUuid));

    ToolExecutionHandle mockHandle = mock(ToolExecutionHandle.class);
    when(boundEnv.execute(any(), any(), any())).thenReturn(mockHandle);

    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.of(boundEnv));

    ToolCall call = new ToolCall("call_local_1", descriptor.name(), "{\"arg1\":\"val1\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(descriptor, call, Duration.ofSeconds(5), context);

    ToolExecutionListener listener = mock(ToolExecutionListener.class);
    ToolExecutionHandle handle = tool.execute(request, listener);

    assertEquals(mockHandle, handle);

    ArgumentCaptor<ToolExecutionRequest> capRequestCaptor =
        ArgumentCaptor.forClass(ToolExecutionRequest.class);
    verify(boundEnv).execute(any(), capRequestCaptor.capture(), eq(listener));

    ToolExecutionRequest capRequest = capRequestCaptor.getValue();
    assertEquals(LocalMcpExecutableTool.CAPABILITY_TOOL_NAME, capRequest.call().toolName());
    assertTrue(capRequest.call().argumentsJson().contains("my_tool"));
  }

  private McpServer createRemoteServer() {
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("remote_server");
    server.setConnectionType(McpConnectionType.REMOTE);
    server.setConnectionConfig("{\"url\":\"" + fakeServer.endpointUrl() + "\",\"headers\":{}}");
    server.setTimeoutMillis(5000L);
    server.setEnabled(true);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setVersion(1L);
    server.setDiscoveredVersion(1L);
    return server;
  }

  private McpServer createLocalServer() {
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("local_server");
    server.setConnectionType(McpConnectionType.LOCAL);
    server.setEnvironmentId(envUuid);
    server.setConnectionConfig(
        "{\"command\":[\"node\",\"server.js\"],\"cwd\":\"/workspace\",\"env\":{\"KEY\":\"VAL\"}}");
    server.setTimeoutMillis(5000L);
    server.setEnabled(true);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setVersion(1L);
    server.setDiscoveredVersion(1L);
    return server;
  }

  private McpTool createTool(String sourceName, String modelName) {
    McpTool tool = new McpTool();
    tool.setId(toolId);
    tool.setServerId(serverId);
    tool.setSourceName(sourceName);
    tool.setModelName(modelName);
    tool.setDescription("sample description");
    tool.setInputSchemaJson("{}");
    tool.setAvailable(true);
    tool.setSchemaRevision(1L);
    return tool;
  }

  private static ToolDescriptor sampleDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        "Sample description",
        "tool",
        new InputSchema(null, Map.of(), Set.of(), true),
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofSeconds(5));
  }
}
