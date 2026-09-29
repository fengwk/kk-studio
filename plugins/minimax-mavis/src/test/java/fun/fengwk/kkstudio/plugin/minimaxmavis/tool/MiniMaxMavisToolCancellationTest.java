package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * MiniMax Mavis Tool 取消与终态回调的并发契约测试。
 *
 * <p>核心不变式：取消与完成回调共享同一个 terminal CAS，因此「已取消」与「已回调完成」永远不会同时被观察到；已经完成的调用不会被 cancel
 * 伪装成取消。取消通过中断正在阻塞的传输发出，与 MCP 工具「取消无需完成回调」的语义一致。
 */
class MiniMaxMavisToolCancellationTest {

  private static final String TOKEN = "mavis-cancel-test-token-98765";
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";
  private static final String CATALOG_RESPONSE = "{\"tools\":[{\"endpoint\":\"web_search\"}]}";

  private ExecutorService executor;
  private BlockingTransport transport;
  private PluginCredentialStore credentialStore;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
    transport = new BlockingTransport();
    credentialStore = new MiniMaxMavisToolExecutionTest.FakeCredentialStore(snapshot());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    transport.release.countDown();
    executor.shutdownNow();
    executor.awaitTermination(2, TimeUnit.SECONDS);
  }

  /** 完成前取消：worker 被中断，终态回调绝不发生，句柄如实报告已取消。 */
  @Test
  void cancelBeforeCompletionSuppressesCompletionCallback() throws Exception {
    Tool tool = tool();
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle = execute(tool, listener);

    assertTrue(transport.invokeEntered.await(2, TimeUnit.SECONDS), "worker must reach the request");
    handle.cancel();
    assertTrue(
        transport.interrupted.await(2, TimeUnit.SECONDS), "cancel must interrupt the request");

    // 等待 worker 线程真正结束，确保没有迟到的回调。
    executor.shutdown();
    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

    assertNull(listener.result(), "a cancelled call must never deliver a completion");
    assertEquals(0, listener.completions.get(), "a cancelled call must not call back at all");
    assertTrue(handle.isCancelled());
  }

  /** 完成后取消：不得伪装成取消，也不得产生第二次回调。 */
  @Test
  void cancelAfterCompletionDoesNotFakeCancellation() throws Exception {
    transport.release.countDown();
    Tool tool = tool();
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle = execute(tool, listener);

    ToolResult delivered = awaitResult(listener);
    assertNotNull(delivered);

    handle.cancel();

    assertFalse(handle.isCancelled(), "a completed call must not report cancellation");
    assertSame(delivered, listener.result(), "completion must be delivered exactly once");
    assertEquals(1, listener.completions.get(), "cancel after completion must not call back again");
  }

  /** 取消与完成并发竞争（真实 latch 同步）：无论谁先赢，永不出现「既已取消又已回调完成」，且最多只有一次终态交付。 */
  @Test
  void cancelAndCompletionRaceNeverDeliverBothOutcomes() throws Exception {
    for (int i = 0; i < 50; i++) {
      BlockingTransport racingTransport = new BlockingTransport();
      RecordingListener listener = new RecordingListener();
      Tool tool = tool(racingTransport, executor);
      ToolExecutionHandle handle = execute(tool, listener);

      assertTrue(racingTransport.invokeEntered.await(2, TimeUnit.SECONDS));
      CountDownLatch start = new CountDownLatch(1);
      Thread canceller =
          new Thread(
              () -> {
                awaitQuietly(start);
                handle.cancel();
              });
      Thread releaser =
          new Thread(
              () -> {
                awaitQuietly(start);
                racingTransport.release.countDown();
              });
      canceller.start();
      releaser.start();
      start.countDown();
      canceller.join(2_000);
      releaser.join(2_000);
      assertTrue(
          racingTransport.finished.await(2, TimeUnit.SECONDS), "the request must reach an end");

      assertFalse(
          listener.completions.get() > 0 && handle.isCancelled(),
          "cancellation and completion callbacks must be mutually exclusive");
      assertTrue(listener.completions.get() <= 1, "at most one completion callback is allowed");
    }
  }

  private Tool tool() {
    return tool(transport, executor);
  }

  private Tool tool(BlockingTransport transport, ExecutorService executor) {
    MavisClient client = new MavisClient(transport);
    return new MiniMaxMavisTool(
        MavisCapability.WEB_SEARCH,
        MavisToolDefinitions.load(MavisCapability.WEB_SEARCH),
        credentialStore,
        client,
        new MiniMaxMavisCapabilityCache(client),
        new MiniMaxMavisResourceAccess(null),
        executor);
  }

  private static ToolExecutionHandle execute(Tool tool, RecordingListener listener) {
    ToolCall call = new ToolCall("call-cancel", tool.descriptor().name(), "{\"query\":\"cancel\"}");
    return tool.execute(
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(120)), listener);
  }

  private static PluginCredentialSnapshot snapshot() {
    return new PluginCredentialSnapshot(
        MiniMaxMavisPlugin.PLUGIN_ID,
        MavisRegion.CN.id().toUpperCase(),
        Instant.now().plusSeconds(3600),
        0L,
        new byte[0],
        new MiniMaxMavisCredentialPayload(TOKEN, CLIENT_UUID, Instant.now()).toJson());
  }

  private static ToolResult awaitResult(RecordingListener listener) throws InterruptedException {
    awaitCondition(() -> listener.result() != null, 2_000);
    return listener.result();
  }

  private static void awaitCondition(BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Condition not met within " + timeoutMillis + "ms");
      }
      Thread.sleep(5);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for the race start");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  /** 记录终态回调次数与最后结果。 */
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

    ToolResult result() {
      return resultRef.get();
    }
  }

  /** catalog 正常响应、MCP invoke 阻塞直到 release 或被中断的假传输。 */
  static final class BlockingTransport implements MavisHttpTransport {
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch invokeEntered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      if (request.url().endsWith("/mavis/api/v1/mcp/tools")) {
        return new MavisHttpResponse(200, CATALOG_RESPONSE);
      }
      invokeEntered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new MavisTransportException("HttpTimeoutException");
        }
        return new MavisHttpResponse(200, "{\"base_resp\":{\"status_code\":0},\"result\":\"ok\"}");
      } catch (InterruptedException error) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new MavisTransportException("InterruptedException", error);
      } finally {
        finished.countDown();
      }
    }
  }
}
