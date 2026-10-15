package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * {@link MiniMaxMavisTool} 的真实取消与本地资源静止（quiescence）契约测试。
 *
 * <p>核心契约约束：
 *
 * <ul>
 *   <li>受控执行体仍在运行时，cancel 只是通知，handle 的静止 stage 绝不能提前完成；
 *   <li>只有执行体真实退出后，quiescence stage 才宣告完成；
 *   <li>cancel-before-run：在执行体运行前取消，抢到执行事实的 cancel 必须立即确认静止，且后续执行绝不得进入执行体；
 *   <li>非中断退出的受控执行体必须在 finally 中释放，确保测试绝不依赖 sleep、不发生资源泄漏。
 * </ul>
 */
class MiniMaxMavisToolQuiescenceTest {

  private static final UUID AUTH_GENERATION =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final String AUTH_CONTEXT = "a1".repeat(32);
  private static final String TOKEN = "mavis-quiescence-test-token";
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";
  private static final String CATALOG_RESPONSE = "{\"tools\":[{\"endpoint\":\"web_search\"}]}";

  private ExecutorService executor;

  @BeforeEach
  void setUp() {
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
   * <p>执行体在收到中断后依然受控等待释放信号（模拟非中断收尾），此时 handle 虽已标记取消，但本地资源静止 stage 绝不提前完成；只有当 release latch
   * 释放且执行体真正退出后，quiescence stage 才宣告完成。 非中断退出无论测试断言成败都必须在 finally 中释放。
   */
  @Test
  void cancelWhileBodyRunningDoesNotCompleteQuiescenceUntilBodyExits() throws Exception {
    ControllableTransport transport = new ControllableTransport();
    Tool tool = createTool(transport, executor);
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle = execute(tool, listener);

    try {
      assertTrue(transport.bodyEntered.await(5, TimeUnit.SECONDS), "工作线程必须已进入能力执行体");

      handle.cancel();
      assertTrue(handle.isCancelled(), "调用 cancel 后 handle 必须立即报告 isCancelled");

      assertTrue(transport.interrupted.await(5, TimeUnit.SECONDS), "取消必须向工作线程发出中断信号");

      // 核心断言：工作线程已被取消并收到中断，但受控执行体尚未退出，quiescence stage 绝不能完成
      assertFalse(
          handle.quiescence().toCompletableFuture().isDone(), "受控执行体仍运行时 cancel 不能完成 stage");

      // 释放受控执行体，允许工作线程真实退出
      transport.release.countDown();

      assertTrue(transport.bodyExited.await(5, TimeUnit.SECONDS), "工作线程必须已真实退出执行体");

      // 等待 quiescence stage 真实完成
      handle.quiescence().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertTrue(handle.quiescence().toCompletableFuture().isDone(), "真实退出后 quiescence stage 才完成");

      assertEquals(0, listener.completionCount(), "已取消的调用不得交付任何终态回调");
    } finally {
      // 无论测试成功或断言失败，非中断退出的受控体必须在 finally 中释放
      transport.release.countDown();
    }
  }

  /**
   * cancel-before-run：任务提交后、工作线程执行前调用 cancel。
   *
   * <p>抢到执行事实的取消必须立即完成静止 stage，且后续任务即使被调度执行，也不得进入执行体本体， 不得产生网络 I/O，也不得向 listener 交付任何完成回调。
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

    RecordingTransport transport = new RecordingTransport();
    Tool tool = createTool(transport, pausedExecutor);
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle = execute(tool, listener);

    assertEquals(1, queue.size(), "worker 任务已提交到执行器队列但未开始运行");

    // 运行前取消
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertTrue(handle.quiescence().toCompletableFuture().isDone(), "cancel-before-run 必须立即可观察到静止");

    // 随后调度该任务运行
    Runnable queuedWorker = queue.poll();
    assertNotNull(queuedWorker);
    queuedWorker.run();

    assertEquals(0, transport.requestCount(), "取消后的任务不得再发起任何网络调用");
    assertEquals(0, listener.completionCount(), "取消后的任务不得向 listener 产生任何终态交付");
    assertTrue(handle.quiescence().toCompletableFuture().isDone());
  }

  /** 同步失败路径（如未绑定媒体网关的生成调用）：未提交异步工作，静止 stage 立即成立。 */
  @Test
  void synchronousFailureReturnsImmediatelyQuiescentHandle() {
    RecordingTransport transport = new RecordingTransport();
    // TTS 在发送前必须要求媒体网关存在，若网关未绑定则同步直接返回 failed 句柄
    MavisClient client = new MavisClient(transport);
    Tool tool =
        new MiniMaxMavisTool(
            MavisCapability.TTS,
            MavisToolDefinitions.load(MavisCapability.TTS),
            new MiniMaxMavisToolExecutionTest.FakeCredentialAccess(snapshot()),
            client,
            new MiniMaxMavisCapabilityCache(client),
            new MiniMaxMavisResourceAccess(null),
            executor);

    RecordingListener listener = new RecordingListener();
    ToolCall call =
        new ToolCall(
            "call-tts-sync-fail",
            tool.descriptor().name(),
            "{\"text\":\"hello\",\"voice_id\":\"male-qn-qingse\"}");
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);

    assertFalse(handle.isCancelled(), "同步失败不是主动取消");
    assertTrue(
        handle.quiescence().toCompletableFuture().isDone(), "同步终结的调用没有任何异步工作，静止 stage 必须立即完成");
    assertEquals(1, listener.completionCount(), "同步失败必须交付错误结果");
    assertTrue(listener.lastResult().error());
  }

  private Tool createTool(MavisHttpTransport transport, ExecutorService executor) {
    MavisClient client = new MavisClient(transport);
    return new MiniMaxMavisTool(
        MavisCapability.WEB_SEARCH,
        MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
        new MiniMaxMavisToolExecutionTest.FakeCredentialAccess(snapshot()),
        client,
        new MiniMaxMavisCapabilityCache(client),
        new MiniMaxMavisResourceAccess(null),
        executor);
  }

  private static ToolExecutionHandle execute(Tool tool, ToolExecutionListener listener) {
    ToolCall call =
        new ToolCall("call-quiescence", tool.descriptor().name(), "{\"query\":\"quiescence\"}");
    return tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);
  }

  private static PluginCredentialSnapshot snapshot() {
    return new PluginCredentialSnapshot(
        MiniMaxMavisPlugin.PLUGIN_ID,
        AUTH_GENERATION,
        AUTH_CONTEXT,
        MavisRegion.CN.id().toUpperCase(),
        Instant.now().plusSeconds(3600),
        0L,
        new byte[0],
        new MiniMaxMavisCredentialPayload(TOKEN, CLIENT_UUID, Instant.now()).toJson());
  }

  /** 受控阻塞、记录中断并在显式 release 后才退出的假传输。 */
  static final class ControllableTransport implements MavisHttpTransport {
    private final CountDownLatch bodyEntered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch bodyExited = new CountDownLatch(1);

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(200, CATALOG_RESPONSE);
      }
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
        throw new MavisTransportException("Cancelled by interrupt", error);
      } finally {
        bodyExited.countDown();
      }
      return new MavisHttpResponse(200, "{\"base_resp\":{\"status_code\":0},\"result\":\"ok\"}");
    }
  }

  /** 仅记录请求次数与提供 catalog 响应的假传输。 */
  static final class RecordingTransport implements MavisHttpTransport {
    private final AtomicInteger requestCount = new AtomicInteger();

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requestCount.incrementAndGet();
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(200, CATALOG_RESPONSE);
      }
      return new MavisHttpResponse(200, "{\"base_resp\":{\"status_code\":0},\"result\":\"ok\"}");
    }

    int requestCount() {
      return requestCount.get();
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
