package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Concurrency tests for dispatcher lifecycle races that do not require a database. */
class PostgresqlExecutionTargetDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

  @Test
  void stopCancelsTimerScheduledAfterStopWinsPublicationRace() throws Exception {
    ExecutionTargetStore store = mock(ExecutionTargetStore.class);
    when(store.findEligibleDue(any(), any(), anyInt())).thenReturn(List.of());
    when(store.findNearestEligibleAvailableAt(any())).thenReturn(Optional.of(NOW.plusSeconds(1)));
    BlockingWakeExecutor wakeExecutor = new BlockingWakeExecutor();
    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> true,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DirectExecutor(),
            wakeExecutor);
    ExecutorService starter = Executors.newSingleThreadExecutor();
    try {
      Future<?> start = starter.submit(dispatcher::start);
      assertTrue(
          wakeExecutor.scheduleEntered.await(2, TimeUnit.SECONDS),
          "drain must reach timer scheduling before stop races with publication");

      dispatcher.stop();
      wakeExecutor.allowScheduleReturn.countDown();
      start.get(2, TimeUnit.SECONDS);

      assertTrue(
          wakeExecutor.scheduled.isCancelled(),
          "a timer scheduled after stop cleared the slot must be cancelled rather than retained");
    } finally {
      wakeExecutor.allowScheduleReturn.countDown();
      starter.shutdownNow();
    }
  }

  @Test
  void timerScanFailureWithWakeResubmitsTheDrain() {
    ExecutionTargetStore store = mock(ExecutionTargetStore.class);
    when(store.findEligibleDue(any(), any(), anyInt())).thenReturn(List.of());
    QueueingExecutor drainExecutor = new QueueingExecutor();
    AtomicReference<PostgresqlExecutionTargetDispatcher> dispatcher = new AtomicReference<>();
    when(store.findNearestEligibleAvailableAt(any()))
        .thenAnswer(
            ignored -> {
              dispatcher.get().wake();
              throw new IllegalStateException("nearest-target scan failed");
            })
        .thenReturn(Optional.empty());
    PostgresqlExecutionTargetDispatcher subject =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> true,
            Clock.fixed(NOW, ZoneOffset.UTC),
            drainExecutor,
            Executors.newSingleThreadScheduledExecutor());
    dispatcher.set(subject);

    subject.start();
    assertTrue(drainExecutor.runNext(), "initial wake must submit one drain");
    assertTrue(
        drainExecutor.runNext(),
        "a wake arriving with the failed timer scan must resubmit a clean drain");
    assertTrue(drainExecutor.isEmpty(), "the recovery drain must complete without another wake");
    subject.stop();
  }

  @Test
  void rejectedWakeSubmissionResetsTheDrainStateForTheNextWake() {
    ExecutionTargetStore store = mock(ExecutionTargetStore.class);
    RejectingExecutor drainExecutor = new RejectingExecutor();
    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> true,
            Clock.fixed(NOW, ZoneOffset.UTC),
            drainExecutor,
            Executors.newSingleThreadScheduledExecutor());

    assertThrows(RejectedExecutionException.class, dispatcher::start);
    assertThrows(RejectedExecutionException.class, dispatcher::wake);
    assertEquals(
        2, drainExecutor.submissions.get(), "the rejection must not leave drainRunning set");
    dispatcher.stop();
  }

  @Test
  void rejectedRecoverySubmissionDoesNotLeaveTheDispatcherMarkedRunning() {
    ExecutionTargetStore store = mock(ExecutionTargetStore.class);
    RejectAfterFirstExecutor drainExecutor = new RejectAfterFirstExecutor();
    AtomicReference<PostgresqlExecutionTargetDispatcher> dispatcher = new AtomicReference<>();
    when(store.findEligibleDue(any(), any(), anyInt())).thenReturn(List.of());
    when(store.findNearestEligibleAvailableAt(any()))
        .thenAnswer(
            ignored -> {
              dispatcher.get().wake();
              throw new IllegalStateException("nearest-target scan failed");
            });
    PostgresqlExecutionTargetDispatcher subject =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> true,
            Clock.fixed(NOW, ZoneOffset.UTC),
            drainExecutor,
            Executors.newSingleThreadScheduledExecutor());
    dispatcher.set(subject);

    subject.start();
    assertTrue(drainExecutor.runFirst(), "initial drain must be accepted");
    assertEquals(
        2,
        drainExecutor.submissions.get(),
        "the failed drain must attempt one recovery submission without escaping its worker");
    assertThrows(RejectedExecutionException.class, subject::wake);
    assertEquals(3, drainExecutor.submissions.get(), "rejected recovery must release drainRunning");
    subject.stop();
  }

  private static class DirectExecutor extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  private static final class QueueingExecutor extends DirectExecutor {

    private final List<Runnable> queued = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      queued.add(command);
    }

    private boolean runNext() {
      if (queued.isEmpty()) {
        return false;
      }
      queued.remove(0).run();
      return true;
    }

    private boolean isEmpty() {
      return queued.isEmpty();
    }
  }

  private static final class RejectingExecutor extends DirectExecutor {

    private final AtomicInteger submissions = new AtomicInteger();

    @Override
    public void execute(Runnable command) {
      submissions.incrementAndGet();
      throw new RejectedExecutionException("drain executor stopped");
    }
  }

  private static final class RejectAfterFirstExecutor extends DirectExecutor {

    private final AtomicInteger submissions = new AtomicInteger();
    private Runnable initial;

    @Override
    public void execute(Runnable command) {
      if (submissions.incrementAndGet() == 1) {
        initial = command;
        return;
      }
      throw new RejectedExecutionException("drain executor stopped");
    }

    private boolean runFirst() {
      if (initial == null) {
        return false;
      }
      initial.run();
      initial = null;
      return true;
    }
  }

  private static final class BlockingWakeExecutor extends DirectExecutor
      implements ScheduledExecutorService {

    private final CountDownLatch scheduleEntered = new CountDownLatch(1);
    private final CountDownLatch allowScheduleReturn = new CountDownLatch(1);
    private final RecordingScheduledFuture scheduled = new RecordingScheduledFuture();

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      scheduleEntered.countDown();
      try {
        if (!allowScheduleReturn.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to return scheduled timer");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while scheduling timer", error);
      }
      return scheduled;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RecordingScheduledFuture implements ScheduledFuture<Object> {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled.set(true);
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }
  }
}
