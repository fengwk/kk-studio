package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpCancelledException;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RemoteMcpExecutableTool} 的真实取消与本地资源静止（quiescence）契约测试。
 *
 * <p>核心契约约束：
 *
 * <ul>
 *   <li>受控执行体仍在运行时，cancel 只是通知，handle 的静止 stage 绝不能提前完成；
 *   <li>只有执行体退出且 MCP client 关闭后，quiescence stage 才宣告完成；
 *   <li>cancel-before-run：在执行体运行前取消，抢到执行事实的 cancel 必须立即确认静止，且后续执行绝不得调用 clientProvider；
 *   <li>非中断退出的受控执行体必须在 finally 中释放，确保测试绝不依赖 sleep、不发生资源泄漏。
 * </ul>
 */
class RemoteMcpExecutableToolQuiescenceTest {

  private static final String SERVER_NAME = "remote_server";
  private static final String TOOL_NAME = "mcp_remote_server_echo";
  private static final String SOURCE_NAME = "echo";

  private McpServerRepository repository;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    repository = mock(McpServerRepository.class);
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() throws Exception {
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * 受控执行体仍运行时 cancel 不能完成 stage，真实退出后才完成。
   *
   * <p>执行体在收到取消中断后依然处于受控运行状态（模拟未完成的网络/本地资源收尾），此时 handle 虽已标记取消， 但本地资源静止 stage 绝不能提前完成；只有在 release
   * latch 释放、执行体退出且 McpClient 安全关闭后， quiescence stage 才能宣告完成。非中断退出无论测试成败必须在 finally 中释放。
   */
  @Test
  void cancelWhileBodyRunningDoesNotCompleteQuiescenceUntilBodyExits() throws Exception {
    ControllableMcpClient client = new ControllableMcpClient();
    ToolDescriptor descriptor = sampleDescriptor();
    stubServerAndTool(descriptor.name(), SOURCE_NAME);

    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            descriptor.name(),
            SOURCE_NAME,
            repository,
            executor,
            (config, deadline) -> client,
            name -> null);

    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall("call-quiesce-1", descriptor.name(), "{}"),
                Duration.ofSeconds(120)),
            listener);

    try {
      assertTrue(client.bodyEntered.await(5, TimeUnit.SECONDS), "工作线程必须已进入 MCP 工具执行体");

      handle.cancel();
      assertTrue(handle.isCancelled(), "调用 cancel 后 handle 必须立即报告 isCancelled");

      assertTrue(client.interrupted.await(5, TimeUnit.SECONDS), "取消必须向工作线程发出中断信号");

      // 核心断言：受控执行体仍运行时 cancel 不能完成 stage
      assertFalse(
          handle.quiescence().toCompletableFuture().isDone(), "受控执行体仍运行时 cancel 不能完成 stage");

      // 释放受控执行体，允许工作线程真实退出
      client.release.countDown();

      assertTrue(client.bodyExited.await(5, TimeUnit.SECONDS), "工作线程必须已真实退出执行体");

      assertTrue(client.clientClosed.await(5, TimeUnit.SECONDS), "MCP client 必须在退出时被关闭");

      // 等待 quiescence stage 真实完成
      handle.quiescence().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertTrue(handle.quiescence().toCompletableFuture().isDone(), "真实退出后 quiescence stage 才完成");

      assertEquals(0, listener.completionCount(), "已取消的调用不得交付任何终态回调");
    } finally {
      // 无论测试成功或断言失败，非中断退出的受控体必须在 finally 中释放
      client.release.countDown();
    }
  }

  /**
   * cancel-before-run：任务提交后、工作线程执行前调用 cancel。
   *
   * <p>抢到执行事实的取消必须立即完成静止 stage，且后续任务即使被调度执行，也不得进入执行体本体， 绝不构造 client，也不得向 listener 交付任何完成回调。
   */
  @Test
  void cancelBeforeRunCompletesQuiescenceImmediatelyAndSuppressesExecution() {
    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    ExecutorService pausedExecutor =
        new AbstractExecutorService() {
          private volatile boolean shutdown;

          @Override
          public void execute(Runnable command) {
            queue.add(command);
          }

          @Override
          public void shutdown() {
            shutdown = true;
          }

          @Override
          public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
          }

          @Override
          public boolean isShutdown() {
            return shutdown;
          }

          @Override
          public boolean isTerminated() {
            return shutdown;
          }

          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
          }
        };

    AtomicInteger clientProviderCalls = new AtomicInteger();
    ToolDescriptor descriptor = sampleDescriptor();
    stubServerAndTool(descriptor.name(), SOURCE_NAME);

    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            descriptor.name(),
            SOURCE_NAME,
            repository,
            pausedExecutor,
            (config, deadline) -> {
              clientProviderCalls.incrementAndGet();
              return mock(McpClient.class);
            },
            name -> null);

    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall("call-before-run-1", descriptor.name(), "{}"),
                Duration.ofSeconds(120)),
            listener);

    assertEquals(1, queue.size(), "worker 任务已提交到执行器队列但未开始运行");

    // 运行前取消
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertTrue(handle.quiescence().toCompletableFuture().isDone(), "cancel-before-run 必须立即可观察到静止");

    // 随后调度该任务运行
    Runnable queuedWorker = queue.poll();
    assertNotNull(queuedWorker);
    queuedWorker.run();

    assertEquals(0, clientProviderCalls.get(), "取消后的任务不得再创建 MCP 客户端或执行调用");
    assertEquals(0, listener.completionCount(), "取消后的任务不得向 listener 产生任何终态交付");
    assertTrue(handle.quiescence().toCompletableFuture().isDone());
  }

  /** 配置失效时（如 Server 不存在）：返回已同步终态的句柄，静止 stage 立即成立。 */
  @Test
  void staleConfigurationReturnsImmediatelyQuiescentHandle() {
    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.empty());
    ToolDescriptor descriptor = sampleDescriptor();

    RemoteMcpExecutableTool tool =
        new RemoteMcpExecutableTool(
            descriptor,
            SERVER_NAME,
            descriptor.name(),
            SOURCE_NAME,
            repository,
            executor,
            (config, deadline) -> mock(McpClient.class),
            name -> null);

    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                descriptor,
                new ToolCall("call-stale-1", descriptor.name(), "{}"),
                Duration.ofSeconds(120)),
            listener);

    assertFalse(handle.isCancelled(), "同步配置失效不是主动取消");
    assertTrue(
        handle.quiescence().toCompletableFuture().isDone(), "同步终结的调用没有任何异步工作，静止 stage 必须立即完成");
    assertEquals(1, listener.completionCount(), "配置失效必须交付错误结果");
    assertTrue(listener.lastResult().error());
  }

  private void stubServerAndTool(String toolName, String sourceName) {
    McpServer server = new McpServer();
    server.setName(SERVER_NAME);
    server.setUrl("https://mock-mcp-server.local");
    server.setHeaders(Map.of());
    server.setEnabled(true);
    server.setTimeoutMillis(5000L);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setVersion(1L);

    McpTool tool = new McpTool();
    tool.setName(toolName);
    tool.setServerName(SERVER_NAME);
    tool.setSourceName(sourceName);
    tool.setDescription("sample description");
    tool.setInputSchemaJson("{}");

    when(repository.getByName(SERVER_NAME)).thenReturn(Optional.of(server));
    when(repository.getTool(toolName)).thenReturn(Optional.of(tool));
  }

  private static ToolDescriptor sampleDescriptor() {
    return new ToolDescriptor(
        TOOL_NAME,
        "sample description",
        "tool",
        new InputSchema(null, Map.of(), Set.of(), true),
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofSeconds(120));
  }

  /** 受控阻塞、记录中断与关闭事件的假 MCP 客户端。 */
  static final class ControllableMcpClient implements McpClient {
    private final CountDownLatch bodyEntered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch bodyExited = new CountDownLatch(1);
    private final CountDownLatch clientClosed = new CountDownLatch(1);

    @Override
    public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
      throw new UnsupportedOperationException("callTool path does not list tools");
    }

    @Override
    public McpToolCallResult callTool(
        String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
      bodyEntered.countDown();
      try {
        while (!release.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting for release latch");
        }
      } catch (InterruptedException error) {
        interrupted.countDown();
        // 收到中断：模拟非中断收尾，依然等待 release 释放才退出
        try {
          while (!release.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for release latch after interrupt");
          }
        } catch (InterruptedException inner) {
          // 再次中断维持中断状态
        }
        Thread.currentThread().interrupt();
        throw new McpCancelledException("cancelled");
      } finally {
        bodyExited.countDown();
      }
      return McpToolCallResult.success(List.of(new TextResultContent("ok")), "{}");
    }

    @Override
    public void close() {
      clientClosed.countDown();
    }
  }

  /** 记录终态回调次数与结果的 listener。 */
  static final class RecordingListener implements ToolExecutionListener {
    private final AtomicReference<ToolResult> resultRef = new AtomicReference<>();
    private final AtomicInteger completions = new AtomicInteger();

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      resultRef.set(outcome.result());
      completions.incrementAndGet();
    }

    @Override
    public void onError(Throwable error) {
      completions.incrementAndGet();
    }

    int completionCount() {
      return completions.get();
    }

    ToolResult lastResult() {
      return resultRef.get();
    }
  }
}
