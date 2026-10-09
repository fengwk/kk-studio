package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 内核的确定性调度回归：分块边界、有界资源、关闭与失败收敛。 */
class TerminalKernelSchedulingTest {

  private static final int TIMEOUT_SECONDS = 5;

  /** 与 {@link TerminalKernel#MAX_INPUT_CHUNK_BYTES} 相等的填充块字节数。 */
  private static final int MAX_FILL_BYTES = 4096;

  private final List<byte[]> responses = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void shutDownExecutors() {
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
  }

  @Test
  void snapshotBeforePartialCsiSuffixThenSuffixStillApplies() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("OK")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      kernel.feed(utf8("\033[38;2;1")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals('O', snapshot(kernel).lines().get(0).slots().get(0).code());

      kernel.feed(utf8(";2;3mX")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView full = snapshot(kernel);
      assertEquals('X', full.lines().get(0).slots().get(2).code());
      assertEquals(
          new TerminalView.Color.Rgb(1, 2, 3),
          full.lines().get(0).slots().get(2).style().foreground());
    }
  }

  @Test
  void resizeBeforePartialCsiSuffixThenSuffixStillApplies() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("OK")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      kernel.feed(utf8("\033[38;2;1")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      kernel.resize(14, 4).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(14, snapshot(kernel).columns());

      kernel.feed(utf8(";2;3mX")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals('X', snapshot(kernel).lines().get(0).slots().get(2).code());
    }
  }

  @Test
  void snapshotCompletesBeforePartialOscSuffix() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("\033]2;partial")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(8, snapshot(kernel).columns());
      kernel.feed(utf8("-title\007Z")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals('Z', snapshot(kernel).lines().get(0).slots().get(0).code());
    }
  }

  @Test
  void utf8TrailingBytesSurviveControlAndResizeBoundaries() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("OKX")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      kernel.feed(new byte[] {(byte) 0xe4, (byte) 0xb8}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(8, snapshot(kernel).columns());

      kernel.resize(16, 4).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      kernel.feed(new byte[] {(byte) 0xad}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView view = snapshot(kernel);
      assertEquals(16, view.columns());
      assertEquals('中', view.lines().get(0).slots().get(3).code());
    }
  }

  @Test
  void controlIsNotStarvedWhileChunkedOscAndCsiAccumulate() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("\033]2;")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      byte[] oscChunk = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
      for (int index = 0; index < 4; index++) {
        kernel.feed(oscChunk).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(8, snapshot(kernel).columns());
      }
      kernel.feed(utf8("\007Z")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals('Z', snapshot(kernel).lines().get(0).slots().get(0).code());
    }

    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("\033[")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      byte[] csiChunk = "0;".repeat(2048).getBytes(StandardCharsets.US_ASCII);
      for (int index = 0; index < 4; index++) {
        kernel.feed(csiChunk).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(8, snapshot(kernel).columns());
      }
      kernel.feed(utf8("mZ")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals('Z', snapshot(kernel).lines().get(0).slots().get(0).code());
    }
  }

  @Test
  void readBudgetOverrunFailsExplicitlyIncludingSynchronizedOutput() throws Exception {
    for (String introducer : List.of("\033[", "\033]2;", "\033P", "\033[?2026h")) {
      // CSI 填充必须是参数字节：'x' 是合法的 CSI 终止字节，会让序列立即结束。
      String unit = introducer.equals("\033[") ? "0;" : "x";
      byte[] chunk =
          unit.repeat(MAX_FILL_BYTES / unit.length()).getBytes(StandardCharsets.US_ASCII);
      try (TerminalKernel kernel = kernel(8, 4)) {
        kernel.feed(utf8(introducer)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (int index = 0; index < TerminalKernel.READ_BUDGET_UNITS / MAX_FILL_BYTES; index++) {
          kernel.feed(chunk).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        ExecutionException failed =
            assertThrows(
                ExecutionException.class,
                () -> kernel.termination().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("terminal read budget exceeded", failed.getCause().getMessage());
        assertTrue(kernel.snapshot().isCompletedExceptionally());
      }
    }
  }

  @Test
  void closeOnEmptyReadTerminatesAndKeepsExecutorOwnedByCaller() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add);
    kernel.close();
    assertFalse(executor.isShutdown());
    assertThrows(
        ExecutionException.class, () -> kernel.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertThrows(
        ExecutionException.class,
        () -> kernel.feed(utf8("A")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  void closeWhilePartialSequencesArePendingStillTerminates() {
    for (String prefix : List.of("\033[123;", "\033]2;unfinished")) {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      executors.add(executor);
      TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add);
      kernel.feed(utf8(prefix));
      kernel.close();
      assertFalse(executor.isShutdown());
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add);
    kernel.feed(new byte[] {(byte) 0xf0, (byte) 0x9f});
    kernel.close();
    assertFalse(executor.isShutdown());
  }

  @Test
  void fullEventQueueRejectsExplicitly() {
    GatedExecutor executor = new GatedExecutor();
    executors.add(executor);
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add);
    try {
      for (int index = 0; index < TerminalKernel.EVENT_QUEUE_CAPACITY; index++) {
        assertFalse(kernel.feed(utf8("A")).isCompletedExceptionally());
      }
      CompletableFuture<Void> overflow = kernel.feed(utf8("A"));
      assertTrue(overflow.isCompletedExceptionally());
    } finally {
      executor.release();
      kernel.close();
    }
  }

  @Test
  void closeBeforeOwnerStartsCancelsEvenWithFullQueue() throws Exception {
    GatedExecutor executor = new GatedExecutor();
    executors.add(executor);
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add);
    List<CompletableFuture<?>> pending = new ArrayList<>();
    for (int index = 0; index < TerminalKernel.EVENT_QUEUE_CAPACITY; index++) {
      pending.add(index % 2 == 0 ? kernel.snapshot() : kernel.feed(utf8("A")));
    }
    try {
      kernel.close();
      kernel.termination().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      for (CompletableFuture<?> future : pending) {
        assertTrue(future.isCompletedExceptionally());
      }
    } finally {
      executor.release();
    }
    assertFalse(executor.isShutdown());
  }

  @Test
  void interruptingOwnerExplicitlyFailsTerminationAndOperations() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    try (TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add)) {
      snapshot(kernel);
      executor.shutdownNow();
      ExecutionException failed =
          assertThrows(
              ExecutionException.class,
              () -> kernel.termination().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("terminal kernel owner interrupted", failed.getCause().getMessage());
      assertTrue(kernel.snapshot().isCompletedExceptionally());
    }
  }

  @Test
  void invalidUtf8IsReplacedAndFeedOwnsItsInputCopy() throws Exception {
    GatedExecutor executor = new GatedExecutor();
    executors.add(executor);
    try (TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, responses::add)) {
      byte[] bytes = {(byte) 0xff, 'A'};
      CompletableFuture<Void> accepted = kernel.feed(bytes);
      bytes[1] = 'X';
      executor.release();
      accepted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView view = snapshot(kernel);
      assertEquals(0xfffd, view.lines().get(0).slots().get(0).code());
      assertEquals('A', view.lines().get(0).slots().get(1).code());
    } finally {
      executor.release();
    }
  }

  @Test
  @Timeout(10)
  void closeInterruptsBlockedResponderAndDrainsFullQueueWithoutLeakingItsError() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    CountDownLatch responding = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    TerminalKernel kernel =
        new TerminalKernel(
            8,
            4,
            8,
            executor,
            bytes -> {
              responding.countDown();
              try {
                gate.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SECRET terminal content");
              }
            });
    try {
      kernel.feed(utf8("\033[6n")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(responding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      List<CompletableFuture<TerminalView>> pending = new ArrayList<>();
      for (int index = 0; index < TerminalKernel.EVENT_QUEUE_CAPACITY; index++) {
        pending.add(kernel.snapshot());
      }
      kernel.close();
      ExecutionException failed =
          assertThrows(ExecutionException.class, () -> kernel.termination().get());
      assertEquals("terminal response delivery failed", failed.getCause().getMessage());
      assertEquals(null, failed.getCause().getCause());
      for (CompletableFuture<?> future : pending) {
        assertTrue(future.isCompletedExceptionally());
      }
    } finally {
      gate.countDown();
      kernel.close();
    }
  }

  @Test
  void closeFromResponderReturnsWithoutWaitingForItself() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    CompletableFuture<TerminalKernel> holder = new CompletableFuture<>();
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, bytes -> holder.join().close());
    holder.complete(kernel);
    kernel.feed(utf8("\033[6n")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    kernel.termination().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void concurrentCloseStillCompletesEveryOfferedFuture() throws Exception {
    ExecutorService owner = Executors.newSingleThreadExecutor();
    executors.add(owner);
    TerminalKernel kernel = new TerminalKernel(8, 4, 8, owner, responses::add);
    List<CompletableFuture<?>> futures = Collections.synchronizedList(new ArrayList<>());
    ExecutorService callers = Executors.newFixedThreadPool(4);
    executors.add(callers);
    CountDownLatch start = new CountDownLatch(1);
    for (int caller = 0; caller < 4; caller++) {
      callers.execute(
          () -> {
            try {
              start.await();
              for (int round = 0; round < 50; round++) {
                futures.add(kernel.snapshot());
                futures.add(kernel.feed(utf8("A")));
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
    }
    start.countDown();
    kernel.close();
    callers.shutdown();
    assertTrue(callers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    for (CompletableFuture<?> future : new ArrayList<>(futures)) {
      try {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (ExecutionException expected) {
        // 关闭竞态下 future 必须明确终结（成功或异常），绝不悬挂。
      }
    }
  }

  @Test
  void responderFailureFailsKernelExplicitly() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    TerminalKernel kernel =
        new TerminalKernel(
            8,
            4,
            8,
            executor,
            bytes -> {
              throw new IllegalStateException("responder rejected outbound data");
            });
    kernel.feed(utf8("\033[6n")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThrows(
        ExecutionException.class, () -> kernel.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    // close 在已终止内核上立即返回，不挂起也不吞异常。
    kernel.close();
  }

  @Test
  void onlyOneResponsePerRequestAndSnapshotGeneratesNone() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("\033[6n")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(8, snapshot(kernel).columns());
      assertEquals(1, responses.size());
      assertTrue(new String(responses.get(0), StandardCharsets.ISO_8859_1).contains("R"));

      kernel.feed(utf8("\033[c")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      snapshot(kernel);
      assertEquals(2, responses.size());
      snapshot(kernel);
      assertEquals(2, responses.size());
    }
  }

  private TerminalKernel kernel(int columns, int rows) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return new TerminalKernel(columns, rows, 8, executor, responses::add);
  }

  private static TerminalView snapshot(TerminalKernel kernel) throws Exception {
    return kernel.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 在放行前不运行 owner 任务，用于确定性地制造满队列。 */
  private static final class GatedExecutor extends AbstractExecutorService {

    private final ExecutorService delegate = Executors.newSingleThreadExecutor();
    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public void execute(Runnable command) {
      delegate.execute(
          () -> {
            try {
              gate.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return;
            }
            command.run();
          });
    }

    void release() {
      gate.countDown();
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
