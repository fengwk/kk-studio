package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** dispatcher 只按本地容量 claim，startup/poll 恢复通知丢失，拒绝时 ownership-fenced 归还。 */
class CanvasFunctionDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");

  private final ExecutorService drain = Executors.newSingleThreadExecutor();
  private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void tearDown() {
    drain.shutdownNow();
    workerExecutor.shutdownNow();
    poll.shutdownNow();
  }

  /** start 必须立即 wake；worker 占满唯一容量时不能继续 claim，释放后由 worker finally 再 wake。 */
  @Test
  void startupWakeAndCapacityPreventOverClaim() throws Exception {
    CanvasFunctionWorkStore store = mock(CanvasFunctionWorkStore.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    ClaimedRun first = claim("first");
    ClaimedRun second = claim("second");
    when(store.claimNext(any(), any(), anyString()))
        .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(worker)
        .run(first);
    CanvasFunctionDispatcher dispatcher =
        dispatcher(store, worker, workerExecutor, properties(1_000));

    dispatcher.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    verify(worker, never()).run(second);
    release.countDown();
    await(
        () -> {
          verify(worker).run(second);
          return true;
        });
    dispatcher.stop();
  }

  /** worker executor 同步拒绝时必须归还 claim，且本次 drain 不再 claim-reject 热循环。 */
  @Test
  void rejectionReschedulesTheClaimAndStopsTheDrain() throws Exception {
    CanvasFunctionWorkStore store = mock(CanvasFunctionWorkStore.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    ClaimedRun claim = claim("rejected");
    when(store.claimNext(any(), any(), anyString())).thenReturn(Optional.of(claim));
    Executor rejecting =
        task -> {
          throw new RejectedExecutionException("full");
        };
    CanvasFunctionDispatcher dispatcher = dispatcher(store, worker, rejecting, properties(1_000));

    dispatcher.start();
    await(
        () -> {
          verify(store).reschedule(any(), any(), any());
          return true;
        });
    verify(store).claimNext(any(), any(), anyString());
    dispatcher.stop();
  }

  /** 没有 NOTIFY 时 fixed-delay poll 仍必须重新 drain 新出现的 READY 行。 */
  @Test
  void periodicPollRecoversLostNotification() throws Exception {
    CanvasFunctionWorkStore store = mock(CanvasFunctionWorkStore.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    ClaimedRun delayed = claim("poll");
    when(store.claimNext(any(), any(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(delayed), Optional.empty());
    CanvasFunctionDispatcher dispatcher = dispatcher(store, worker, workerExecutor, properties(20));

    dispatcher.start();
    await(
        () -> {
          verify(worker).run(delayed);
          return true;
        });
    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
    verify(store, atLeastOnce()).claimNext(any(), any(), anyString());
  }

  /** lifecycle 必须幂等，start 前/stop 后 wake 不触库，stop callback 必达且 stop 后禁止重启。 */
  @Test
  void lifecycleIsIdempotentAndClosesCleanly() {
    CanvasFunctionWorkStore store = mock(CanvasFunctionWorkStore.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    when(store.claimNext(any(), any(), anyString())).thenReturn(Optional.empty());
    CanvasFunctionDispatcher dispatcher =
        dispatcher(store, worker, workerExecutor, properties(1_000));

    dispatcher.wake();
    verify(store, never()).claimNext(any(), any(), anyString());
    dispatcher.start();
    dispatcher.start();
    dispatcher.stop();
    dispatcher.stop();
    AtomicBoolean callback = new AtomicBoolean();
    dispatcher.stop(() -> callback.set(true));
    dispatcher.wake();

    assertTrue(callback.get());
    assertFalse(dispatcher.isRunning());
    assertTrue(dispatcher.isAutoStartup());
    assertEquals(Integer.MAX_VALUE - 1, dispatcher.getPhase());
    assertThrows(IllegalStateException.class, dispatcher::start);
    dispatcher.close();
  }

  /** drain executor 拒绝与 store 扫描异常都只放弃本次 drain，后续 wake 仍可恢复。 */
  @Test
  void drainFailuresReleaseTheSingleDrainGate() throws Exception {
    CanvasFunctionWorkStore store = mock(CanvasFunctionWorkStore.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    Executor rejectingDrain =
        task -> {
          throw new RejectedExecutionException("drain full");
        };
    CanvasFunctionDispatcher rejected =
        new CanvasFunctionDispatcher(
            store,
            properties(1_000),
            Clock.fixed(NOW, ZoneOffset.UTC),
            rejectingDrain,
            workerExecutor,
            poll,
            worker);
    rejected.start();
    rejected.wake();
    verify(store, never()).claimNext(any(), any(), anyString());
    rejected.stop();

    when(store.claimNext(any(), any(), anyString()))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(Optional.empty());
    CanvasFunctionDispatcher retryable =
        dispatcher(store, worker, workerExecutor, properties(1_000));
    retryable.start();
    await(
        () -> {
          verify(store, atLeastOnce()).claimNext(any(), any(), anyString());
          return true;
        });
    retryable.wake();
    retryable.stop();
  }

  private CanvasFunctionDispatcher dispatcher(
      CanvasFunctionWorkStore store,
      CanvasFunctionWorker worker,
      Executor workers,
      CanvasFunctionRuntimeProperties properties) {
    return new CanvasFunctionDispatcher(
        store, properties, Clock.fixed(NOW, ZoneOffset.UTC), drain, workers, poll, worker);
  }

  private static CanvasFunctionRuntimeProperties properties(long pollMillis) {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.setWorkerConcurrency(1);
    properties.setMaxDispatchTasks(1);
    properties.setPollIntervalMillis(pollMillis);
    return properties;
  }

  private static ClaimedRun claim(String seed) {
    UUID nodeId = UUID.nameUUIDFromBytes(("node-" + seed).getBytes(StandardCharsets.UTF_8));
    UUID requestId = UUID.nameUUIDFromBytes(("request-" + seed).getBytes(StandardCharsets.UTF_8));
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            nodeId,
            requestId,
            CanvasFunctionRunStatus.RUNNING,
            1,
            null,
            "lease-" + seed,
            NOW.plusSeconds(30),
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            NOW,
            NOW);
    return new ClaimedRun(run);
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    AssertionError last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (check.done()) {
          return;
        }
      } catch (AssertionError error) {
        last = error;
      }
      Thread.sleep(5);
    }
    if (last != null) {
      throw last;
    }
    throw new AssertionError("condition not met");
  }

  @FunctionalInterface
  private interface Check {
    boolean done();
  }
}
