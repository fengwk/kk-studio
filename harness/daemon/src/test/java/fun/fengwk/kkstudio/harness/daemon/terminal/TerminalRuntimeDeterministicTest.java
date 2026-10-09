package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.process.ProcessScope;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * {@link TerminalRuntime} 的确定性边界回归：窄构造入口注入真实 {@link ProcessScope} 与可替换流，构造满队列、partial-write 与
 * blocked-write。用真进程只负责 scope 生命周期（hold），数据面完全由测试流控制，断言因此不依赖时序运气。
 */
class TerminalRuntimeDeterministicTest {

  private static final long WAIT_SECONDS = 10L;

  @TempDir Path workdir;

  private final List<ExecutorService> executors = new ArrayList<>();
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void shutDownExecutors() {
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
    for (ScheduledExecutorService scheduler : schedulers) {
      scheduler.shutdownNow();
    }
  }

  @Test
  void staleInputModeIsRejectedWithoutWritingAndWithoutEndingTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      RecordingOutputStream output = new RecordingOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        CompletableFuture<Void> stale = runtime.writeInput(bytes("x"), 99L);
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> stale.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(
            failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
            "过期模式是确定未执行，不是结果不确定");
        assertEquals("", output.capturedAsString(), "过期模式的输入不得进入 PTY");

        // 会话仍然存活：以真实当前版本（初始为 1）写入成功。
        CompletableFuture<Void> accepted = runtime.writeInput(bytes("y"), 1L);
        accepted.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("y", output.capturedAsString());
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void invalidArgumentsAreRejectedSynchronously() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          runtime(scope, new BlockingInputStream(), new RecordingOutputStream());
      try {
        assertThrows(IllegalArgumentException.class, () -> runtime.writeInput(new byte[0], 1L));
        assertThrows(
            IllegalArgumentException.class,
            () -> runtime.writeInput(new byte[TerminalRuntime.MAX_USER_FRAME_BYTES + 1], 1L));
        assertThrows(IllegalArgumentException.class, () -> runtime.writeInput(bytes("a"), 0L));
        assertThrows(IllegalArgumentException.class, () -> runtime.resize(4, 5));
        assertThrows(IllegalArgumentException.class, () -> runtime.resize(301, 5));
        assertThrows(IllegalArgumentException.class, () -> runtime.resize(20, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.resize(20, 101));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void internalQueryResponseGoesThroughTheSameWriter() throws Exception {
    try (ProcessScope scope = holdScope()) {
      RecordingOutputStream output = new RecordingOutputStream();
      TerminalRuntime runtime = runtime(scope, new ScriptedInputStream(bytes("\u001b[c")), output);
      try {
        awaitTrue(() -> output.contains("\u001b[?6c"), WAIT_SECONDS);
        assertTrue(
            output.contains("\u001b[?6c"),
            "内核的 Primary DA 应答必须经唯一写队列回到 PTY，实际捕获：" + output.capturedAsString());
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void userFramesAreWrittenWholeAndInFifoOrder() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        CompletableFuture<Void> first = runtime.writeInput(bytes("AAA"), 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS), "首帧必须开始 native 写");
        CompletableFuture<Void> second = runtime.writeInput(bytes("BBB"), 1L);
        CompletableFuture<Void> third = runtime.writeInput(bytes("CCC"), 1L);
        output.release();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        third.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("AAABBBCCC", output.capturedAsString(), "帧不得交叉，顺序必须保持 FIFO");
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void queueCapacityBoundsInFlightPlusQueuedItems() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        runtime.writeInput(bytes("0"), 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        for (int index = 0; index < TerminalRuntime.WRITE_QUEUE_CAPACITY - 1; index++) {
          accepted.add(runtime.writeInput(bytes("x"), 1L));
        }
        for (CompletableFuture<Void> future : accepted) {
          assertFalse(future.isDone(), "容量内的排队项不得被拒绝");
        }
        CompletableFuture<Void> rejected = runtime.writeInput(bytes("y"), 1L);
        assertTrue(rejected.isDone(), "超出容量的写入必须同步拒绝");
        ExecutionException failure =
            assertThrows(
                ExecutionException.class, () -> rejected.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException);
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void byteBudgetBoundsPendingBytesBeforeCapacity() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        runtime.writeInput(new byte[TerminalRuntime.MAX_USER_FRAME_BYTES], 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
        // 一个 64 KiB 内部帧加上 writing 的 4 KiB，再按 4 KiB 用户帧填充到字节预算上限。
        runtime.onKernelResponse(new byte[TerminalRuntime.MAX_INTERNAL_FRAME_BYTES]);
        int accepted = 0;
        CompletableFuture<Void> rejected = null;
        for (int index = 0; index < TerminalRuntime.WRITE_QUEUE_CAPACITY; index++) {
          CompletableFuture<Void> future =
              runtime.writeInput(new byte[TerminalRuntime.MAX_USER_FRAME_BYTES], 1L);
          if (future.isDone()) {
            rejected = future;
            break;
          }
          accepted++;
        }
        assertTrue(rejected != null, "字节预算耗尽后必须拒绝后续写入");
        assertEquals(111, accepted, "字节预算（含 writing 与内部帧）必须先于容量耗尽");
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        pending.add(rejected);
        for (CompletableFuture<Void> future : pending) {
          assertThrows(ExecutionException.class, () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void cancelledQueuedOperationIsNotWritten() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        CompletableFuture<Void> first = runtime.writeInput(bytes("AAA"), 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
        CompletableFuture<Void> cancelled = runtime.writeInput(bytes("BBB"), 1L);
        CompletableFuture<Void> third = runtime.writeInput(bytes("CCC"), 1L);
        assertTrue(cancelled.cancel(false));
        output.release();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        third.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("AAACCC", output.capturedAsString(), "取消的排队项不得写入 PTY");
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void partialWriteIsOutcomeUnknownAndEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          runtime(scope, new BlockingInputStream(), new FailingOutputStream(2));
      CompletableFuture<Void> write = runtime.writeInput(bytes("hello"), 1L);
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> write.get(WAIT_SECONDS, TimeUnit.SECONDS));
      assertTrue(
          failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
          "部分写入可能已有前缀，结果必须是不确定");
      assertTrue(
          assertThrows(
                      ExecutionException.class,
                      () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS))
                  .getCause()
              instanceof IllegalStateException,
          "写入失败必须终结整个会话");
    }
  }

  @Test
  void writeTimeoutIsOutcomeUnknownAndEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      FastTimeoutScheduler scheduler = new FastTimeoutScheduler();
      schedulers.add(scheduler);
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope,
              new BlockingInputStream(),
              new BlockingOutputStream(),
              20,
              5,
              8,
              vtExecutor(),
              ioExecutor(),
              scheduler);
      CompletableFuture<Void> write = runtime.writeInput(bytes("hello"), 1L);
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> write.get(WAIT_SECONDS, TimeUnit.SECONDS));
      assertTrue(
          failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
          "超时的 native 写可能已写前缀，结果必须是不确定");
      assertThrows(
          ExecutionException.class,
          () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    }
  }

  @Test
  void closeIsIdempotentTerminatesPendingOperationsAndLeavesExecutorsRunning() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      ExecutorService vt = vtExecutor();
      ExecutorService io = ioExecutor();
      ScheduledExecutorService scheduler = scheduler();
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope, new BlockingInputStream(), output, 20, 5, 8, vt, io, scheduler);
      CompletableFuture<Void> inFlight = runtime.writeInput(bytes("AAA"), 1L);
      assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
      CompletableFuture<Void> queued = runtime.writeInput(bytes("BBB"), 1L);
      runtime.close();
      runtime.close();
      assertTrue(runtime.termination().isDone(), "重复 close 必须立即返回且收敛完成");
      assertTrue(inFlight.isCompletedExceptionally(), "在途写入不得遗留 pending");
      assertTrue(queued.isCompletedExceptionally(), "排队写入不得遗留 pending");
      assertFalse(vt.isShutdown(), "运行时不得关闭调用方 executor");
      assertFalse(io.isShutdown(), "运行时不得关闭调用方 executor");
      assertFalse(scheduler.isShutdown(), "运行时不得关闭调用方 scheduler");
      assertFalse(scope.process().isAlive(), "关闭必须收敛进程范围");
      assertTrue(runtime.writeInput(bytes("z"), 1L).isCompletedExceptionally(), "关闭后不得再接受写入");
      assertTrue(runtime.resize(20, 5).isCompletedExceptionally(), "关闭后不得再接受尺寸调整");
    }
  }

  @Test
  void rejectedVtExecutorFailsConstructionWithoutStartingTheKernel() throws Exception {
    ProcessScope scope = holdScope();
    try {
      ExecutorService shutdown = Executors.newSingleThreadExecutor();
      shutdown.shutdownNow();
      assertThrows(
          IllegalStateException.class,
          () ->
              new TerminalRuntime(
                  scope,
                  new BlockingInputStream(),
                  new RecordingOutputStream(),
                  20,
                  5,
                  8,
                  shutdown,
                  ioExecutor(),
                  scheduler()));
    } finally {
      scope.close();
    }
  }

  @Test
  void rejectedIoExecutorFailsConstructionAndClosesTheKernel() throws Exception {
    ProcessScope scope = holdScope();
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              new TerminalRuntime(
                  scope,
                  new BlockingInputStream(),
                  new RecordingOutputStream(),
                  20,
                  5,
                  8,
                  vtExecutor(),
                  new RejectingExecutor(),
                  scheduler()));
    } finally {
      scope.close();
    }
  }

  @Test
  void startRejectsInvalidLaunchDimensionsBeforeNativeStart() {
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec("/bin/sh", List.of(), workdir.toAbsolutePath());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerminalRuntime.start(
                spec, 4, 5, 8, Map.of(), () -> true, vtExecutor(), ioExecutor(), scheduler()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerminalRuntime.start(
                spec, 20, 5, 513, Map.of(), () -> true, vtExecutor(), ioExecutor(), scheduler()));
  }

  @Test
  void resizeCompletesThroughKernelThenNativeWindow() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          runtime(scope, new BlockingInputStream(), new RecordingOutputStream());
      try {
        runtime.resize(30, 6).get(WAIT_SECONDS, TimeUnit.SECONDS);
        TerminalView view = runtime.snapshot().get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(30, view.columns(), "内核权威尺寸必须已改变");
        assertEquals(6, view.rows());
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void unavailableWriteDeadlineIsDeterminateAndEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope,
              new BlockingInputStream(),
              new RecordingOutputStream(),
              20,
              5,
              8,
              vtExecutor(),
              ioExecutor(),
              new RejectingScheduler());
      try {
        CompletableFuture<Void> write = runtime.writeInput(bytes("hello"), 1L);
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> write.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(
            failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
            "无法建立截止时间时该帧确定未执行，不是结果不确定");
        assertThrows(
            ExecutionException.class,
            () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void unavailableResizeDeadlineIsUncertainAndEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope,
              new BlockingInputStream(),
              new RecordingOutputStream(),
              20,
              5,
              8,
              vtExecutor(),
              ioExecutor(),
              new RejectingScheduler());
      try {
        CompletableFuture<Void> resize = runtime.resize(30, 6);
        ExecutionException failure =
            assertThrows(
                ExecutionException.class, () -> resize.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(
            failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
            "内核尺寸已改而窗口未改是部分变更，结果必须是不确定");
        assertThrows(
            ExecutionException.class,
            () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void readFailureEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      TerminalRuntime runtime =
          runtime(scope, new ThrowingInputStream(), new RecordingOutputStream());
      try {
        assertThrows(
            ExecutionException.class,
            () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void fullResponseQueueFailsTheKernelAndEndsTheSession() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      DeferredInputStream input = new DeferredInputStream(bytes("\u001b[c"));
      TerminalRuntime runtime = runtime(scope, input, output);
      try {
        runtime.writeInput(bytes("0"), 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
        for (int index = 0; index < TerminalRuntime.WRITE_QUEUE_CAPACITY - 1; index++) {
          runtime.writeInput(bytes("x"), 1L);
        }
        // 队列已满时内核仍要投递 Primary DA 应答，应答不可丢弃，因此内核明确失败。
        input.release();
        assertThrows(
            ExecutionException.class,
            () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(runtime.snapshot().isCompletedExceptionally(), "内核失败后不得返回画面");
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void cancelAfterNativeStartedDoesNotFalselyClaimTheFrameWasNotWritten() throws Exception {
    try (ProcessScope scope = holdScope()) {
      GateOutputStream output = new GateOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        CompletableFuture<Void> write = runtime.writeInput(bytes("AAA"), 1L);
        assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS), "首帧必须已进入 native 写");
        // native 已经开始后调用方取消 future：取消不能成为「未写」的证据，native 仍必须落笔。
        assertTrue(write.cancel(false));
        output.release();
        awaitTrue(() -> output.capturedAsString().equals("AAA"), WAIT_SECONDS);
        assertEquals("AAA", output.capturedAsString(), "native 已开始，调用方取消不得阻止或否认写入");
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void lateDeadlineAfterCompletedWriteIsIgnoredAndSessionSurvives() throws Exception {
    try (ProcessScope scope = holdScope()) {
      ManualScheduler scheduler = new ManualScheduler();
      schedulers.add(scheduler);
      RecordingOutputStream output = new RecordingOutputStream();
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope,
              new BlockingInputStream(),
              output,
              20,
              5,
              8,
              vtExecutor(),
              ioExecutor(),
              scheduler);
      try {
        runtime.writeInput(bytes("hello"), 1L).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("hello", output.capturedAsString());
        // 在已完成之后才到达（甚至已越过 cancel）的截止时间必须被忽略，不得杀掉已完成的会话。
        scheduler.fireAll();
        assertFalse(runtime.termination().isDone(), "已完成的 native 写不得被迟到的截止时间终止");
        assertFalse(runtime.snapshot().isCompletedExceptionally(), "会话必须保持可用");
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void unresponsiveNativeWriteIsReportedAsUncertainAndTaskFailure() throws Exception {
    try (ProcessScope scope = holdScope()) {
      UninterruptibleOutputStream output = new UninterruptibleOutputStream();
      TerminalRuntime runtime = runtime(scope, new BlockingInputStream(), output);
      try {
        CompletableFuture<Void> write = runtime.writeInput(bytes("stuck"), 1L);
        assertTrue(output.awaitEntered(WAIT_SECONDS, TimeUnit.SECONDS), "写任务必须已进入 native 写");
        // 忽略中断的 native 写无法收尾：pending 必须显式终结为不确定，收敛必须报告任务失败。
        runtime.close();
        assertTrue(write.isCompletedExceptionally(), "无法收尾的在途写不得遗留 pending");
        assertTrue(runtime.termination().isCompletedExceptionally(), "任务无法收尾必须显式失败");
      } finally {
        output.release();
        runtime.close();
      }
    }
  }

  @Test
  void abandonedIoExecutorStillConvergesAndReportsExplicitFailure() throws Exception {
    ProcessScope scope = holdScope();
    try {
      ExecutorService io = ioExecutor();
      TerminalRuntime runtime =
          new TerminalRuntime(
              scope,
              new BlockingInputStream(),
              new RecordingOutputStream(),
              20,
              5,
              8,
              vtExecutor(),
              io,
              scheduler());
      // 调用方提前放弃阻塞 I/O executor：运行时仍必须有界收敛并显式报告会话失败。
      io.shutdownNow();
      runtime.close();
      assertTrue(runtime.termination().isCompletedExceptionally(), "调用方放弃执行器必须显式失败");
    } finally {
      scope.close();
    }
  }

  @Test
  void startRejectsDeniedGateWithoutStartingTheCommand() throws Exception {
    List<String> command =
        TerminalRuntimeFixtureMain.fixtureCommand("hold", workdir.resolve("denied.pid").toString());
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(
            command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                TerminalRuntime.start(
                    spec,
                    20,
                    5,
                    8,
                    Map.of(),
                    () -> false,
                    vtExecutor(),
                    ioExecutor(),
                    scheduler()));
    assertEquals("terminal runtime could not be started", failure.getMessage());
    assertFalse(Files.exists(workdir.resolve("denied.pid")), "拒绝的闸门不得启动用户命令");
  }

  @Test
  void rejectedWriterSubmissionAbortsLifecycleWithoutLeakingTasks() throws Exception {
    ProcessScope scope = holdScope();
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              new TerminalRuntime(
                  scope,
                  new BlockingInputStream(),
                  new RecordingOutputStream(),
                  20,
                  5,
                  8,
                  vtExecutor(),
                  new PartiallyRejectingExecutor(ioExecutor(), 2),
                  scheduler()));
    } finally {
      scope.close();
    }
  }

  @Test
  void resizeNativeFailureIsUncertainAndEndsTheSession() throws Exception {
    List<String> command =
        TerminalRuntimeFixtureMain.fixtureCommand(
            "hold", workdir.resolve("capture.pid").toString());
    try (ProcessScope scope = ProcessScope.start(workdir, command)) {
      TerminalRuntime runtime =
          runtime(scope, new BlockingInputStream(), new RecordingOutputStream());
      try {
        // 捕获模式 scope 的 resize 会显式失败：内核已 resize 而窗口未调整，属于部分变更。
        CompletableFuture<Void> resize = runtime.resize(30, 6);
        ExecutionException failure =
            assertThrows(
                ExecutionException.class, () -> resize.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(
            failure.getCause() instanceof TerminalRuntime.OutcomeUnknownException,
            "PTY 窗口调整失败时结果必须是不确定");
        assertThrows(
            ExecutionException.class,
            () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void zeroLengthReadIsRetriedAndInternalResponsesAreDroppedWhenClosing() throws Exception {
    try (ProcessScope scope = holdScope()) {
      RecordingOutputStream output = new RecordingOutputStream();
      TerminalRuntime runtime = runtime(scope, new ZeroReadInputStream(), output);
      try {
        runtime.writeInput(bytes("q"), 1L).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("q", output.capturedAsString());
        assertFalse(runtime.termination().isDone(), "零长读不得被当成 EOF 结束会话");
        assertThrows(
            IllegalStateException.class,
            () -> runtime.onKernelResponse(new byte[TerminalRuntime.MAX_INTERNAL_FRAME_BYTES + 1]));
        runtime.onKernelResponse(null);
        runtime.onKernelResponse(new byte[0]);
        runtime.close();
        // 会话收敛中的查询应答被丢弃，而不是把内核判定为失败。
        runtime.onKernelResponse(bytes("\u001b[?6c"));
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void startClosesScopeWhenRuntimeConstructionFails() throws Exception {
    List<String> command =
        TerminalRuntimeFixtureMain.fixtureCommand(
            "hold", workdir.resolve("rejected.pid").toString());
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(
            command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath());
    assertThrows(
        IllegalStateException.class,
        () ->
            TerminalRuntime.start(
                spec,
                20,
                5,
                8,
                System.getenv(),
                () -> true,
                vtExecutor(),
                new RejectingExecutor(),
                scheduler()));
  }

  @Test
  void unresolvableExecutableFailsTheSessionWithoutLeakingItsPath() throws Exception {
    Path missing = workdir.resolve("definitely-not-an-executable");
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(missing.toString(), List.of(), workdir.toAbsolutePath());
    TerminalRuntime runtime =
        TerminalRuntime.start(
            spec, 20, 5, 8, System.getenv(), () -> true, vtExecutor(), ioExecutor(), scheduler());
    try {
      ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () -> runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
      assertNull(runtime.exitCode(), "异常退出没有自然退出码");
      assertFalse(
          String.valueOf(failure.getMessage()).contains(missing.getFileName().toString()),
          "失败说明不得回显启动路径");
    } finally {
      runtime.close();
    }
  }

  private TerminalRuntime runtime(ProcessScope scope, InputStream input, OutputStream output) {
    return new TerminalRuntime(
        scope, input, output, 20, 5, 8, vtExecutor(), ioExecutor(), scheduler());
  }

  private ProcessScope holdScope() throws IOException {
    return ProcessScope.startPty(
        workdir,
        TerminalRuntimeFixtureMain.fixtureCommand(
            "hold", workdir.resolve("hold-" + UUID.randomUUID() + ".pid").toString()),
        80,
        24,
        System.getenv(),
        () -> true);
  }

  private ExecutorService vtExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private ExecutorService ioExecutor() {
    ExecutorService executor = Executors.newCachedThreadPool();
    executors.add(executor);
    return executor;
  }

  private ScheduledExecutorService scheduler() {
    ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor();
    schedulers.add(scheduled);
    return scheduled;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static void awaitTrue(BooleanSupplier condition, long seconds)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        return;
      }
      Thread.sleep(10L);
    }
  }

  /** 记录每次写入的原始字节，用于断言帧内容与顺序。 */
  static final class RecordingOutputStream extends OutputStream {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int value) {
      captured.write(value);
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) {
      captured.write(data, offset, length);
    }

    synchronized String capturedAsString() {
      return new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    synchronized boolean contains(String text) {
      return capturedAsString().contains(text);
    }
  }

  /** 首次 native 写阻塞在闸门上，用于确定性地构造「写者被占住」的窗口。 */
  static final class GateOutputStream extends OutputStream {

    private final CountDownLatch firstWrite = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private int writes;

    @Override
    public synchronized void write(int value) {
      captured.write(value);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      boolean gate;
      synchronized (this) {
        gate = writes++ == 0;
      }
      if (gate) {
        firstWrite.countDown();
        awaitRelease();
      }
      synchronized (this) {
        captured.write(data, offset, length);
      }
    }

    private void awaitRelease() throws IOException {
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("gate interrupted");
      }
    }

    boolean awaitFirstWrite(long timeout, TimeUnit unit) throws InterruptedException {
      return firstWrite.await(timeout, unit);
    }

    void release() {
      release.countDown();
    }

    synchronized String capturedAsString() {
      return new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
    }
  }

  /** 先写入若干前缀字节再抛异常，构造 partial-write。 */
  static final class FailingOutputStream extends OutputStream {

    private final int prefix;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    FailingOutputStream(int prefix) {
      this.prefix = prefix;
    }

    @Override
    public synchronized void write(int value) {
      captured.write(value);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      int count = Math.min(prefix, length);
      synchronized (this) {
        captured.write(data, offset, count);
      }
      throw new IOException("fixture write failure");
    }
  }

  /** native 写永久阻塞，直到被中断。 */
  static final class BlockingOutputStream extends OutputStream {

    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public void write(int value) throws IOException {
      await();
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      await();
    }

    private void await() throws IOException {
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("write interrupted");
      }
      throw new IOException("blocking output released");
    }
  }

  /** 先投递脚本字节再永久阻塞，用于让内核读到输入而读任务保持在位。 */
  static final class ScriptedInputStream extends InputStream {

    private final byte[] data;
    private final CountDownLatch gate = new CountDownLatch(1);
    private int index;

    ScriptedInputStream(byte[] data) {
      this.data = data;
    }

    @Override
    public synchronized int read() throws IOException {
      if (index < data.length) {
        return data[index++] & 0xff;
      }
      return block();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      synchronized (this) {
        if (index < data.length) {
          int count = Math.min(length, data.length - index);
          System.arraycopy(data, index, buffer, offset, count);
          index += count;
          return count;
        }
      }
      return block();
    }

    private int block() throws IOException {
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
      return -1;
    }
  }

  /** 永久阻塞，直到读任务被中断。 */
  static final class BlockingInputStream extends InputStream {

    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      return block();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      return block();
    }

    private int block() throws IOException {
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
      return -1;
    }
  }

  /** 提交即拒绝的执行器：构造失败路径必须显式失败且无用户命令副作用。 */
  static final class RejectingExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("fixture rejection");
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
  }

  /** 提交即拒绝的 scheduler：用于确定性地构造「无法建立写/尺寸截止时间」。 */
  static final class RejectingScheduler extends ScheduledThreadPoolExecutor {

    RejectingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("fixture rejection");
    }
  }

  /** 第一次读取就抛异常，构造 PTY read failure。 */
  static final class ThrowingInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      throw new IOException("fixture read failure");
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      throw new IOException("fixture read failure");
    }
  }

  /** 调度后仍先阻塞，直到测试显式释放才投递脚本字节：用于确定性地把「队列已满」与「内核应答」重叠。 */
  static final class DeferredInputStream extends InputStream {

    private final byte[] data;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch overflow = new CountDownLatch(1);
    private int index;

    DeferredInputStream(byte[] data) {
      this.data = data;
    }

    void release() {
      started.countDown();
    }

    @Override
    public int read() throws IOException {
      awaitStart();
      synchronized (this) {
        if (index < data.length) {
          return data[index++] & 0xff;
        }
      }
      return block();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      awaitStart();
      synchronized (this) {
        if (index < data.length) {
          int count = Math.min(length, data.length - index);
          System.arraycopy(data, index, buffer, offset, count);
          index += count;
          return count;
        }
      }
      return block();
    }

    private void awaitStart() throws IOException {
      try {
        started.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
    }

    private int block() throws IOException {
      try {
        overflow.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
      return -1;
    }
  }

  /** 忽略延迟、立即触发调度任务，用于确定性地构造写截止超时而不真等 10 秒。 */
  static final class FastTimeoutScheduler extends ScheduledThreadPoolExecutor {

    FastTimeoutScheduler() {
      super(2);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return super.schedule(command, 0L, unit);
    }
  }

  /** native 写忽略中断并一直阻塞，直到测试显式释放：用于构造无法收尾的写任务。 */
  static final class UninterruptibleOutputStream extends OutputStream {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      entered.countDown();
      while (true) {
        try {
          release.await();
          break;
        } catch (InterruptedException ignored) {
          // 故意忽略中断：模拟无法用 interrupt 收尾的 native 写。
        }
      }
      throw new IOException("unblocked");
    }

    boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
      return entered.await(timeout, unit);
    }

    void release() {
      release.countDown();
    }
  }

  /** 先投递一次零长读、其后永久阻塞：用于证明零长读不会被当成 EOF。 */
  static final class ZeroReadInputStream extends InputStream {

    private final CountDownLatch gate = new CountDownLatch(1);
    private boolean zeroReturned;

    @Override
    public int read() throws IOException {
      return block();
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
      if (!zeroReturned) {
        zeroReturned = true;
        return 0;
      }
      return block();
    }

    private int block() throws IOException {
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
      return -1;
    }
  }

  /** 前 {@code limit} 次提交委托真实执行器，其后拒绝：构造 failure 覆盖「lifecycle 已预留但后续任务提交失败」。 */
  static final class PartiallyRejectingExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final int limit;
    private final AtomicInteger accepted = new AtomicInteger();

    PartiallyRejectingExecutor(ExecutorService delegate, int limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public void execute(Runnable command) {
      if (accepted.incrementAndGet() > limit) {
        throw new RejectedExecutionException("fixture rejection");
      }
      delegate.execute(command);
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

  /** 只登记截止任务，由测试显式触发；模拟已经越过 cancel 的迟到截止时间。 */
  static final class ManualScheduler extends ScheduledThreadPoolExecutor {

    private final List<Runnable> deadlines = new CopyOnWriteArrayList<>();

    ManualScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      deadlines.add(command);
      return super.schedule(() -> {}, delay, unit);
    }

    void fireAll() {
      for (Runnable deadline : deadlines) {
        deadline.run();
      }
    }
  }
}
