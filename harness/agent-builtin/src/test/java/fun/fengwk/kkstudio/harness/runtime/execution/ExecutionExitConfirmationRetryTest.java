package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ExecutionExitConfirmationRetryTest {

  @Test
  void startIsIdempotent() {
    ObservingJournal journal = new ObservingJournal();
    ExecutionExitConfirmer confirmer = confirmer(journal);
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    ExecutorService worker = mock(ExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future)
        .when(timer)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));

    try (ExecutionExitConfirmationRetry retry =
        new ExecutionExitConfirmationRetry(confirmer, timer, worker)) {
      retry.start();
      retry.start();
      verify(timer, times(1))
          .scheduleWithFixedDelay(
              any(Runnable.class), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    }
    verify(future).cancel(false);
    verify(timer).shutdownNow();
    verify(worker).shutdownNow();
  }

  @Test
  void replayRunsOnWorkerAndHeldReplayDoesNotOverlap() throws Exception {
    ObservingJournal journal = new ObservingJournal();
    journal.block = true;
    ExecutionExitConfirmer confirmer = confirmer(journal);

    try (ExecutionExitConfirmationRetry retry = new ExecutionExitConfirmationRetry(confirmer)) {
      retry.start();
      assertTrue(journal.entered.await(2, TimeUnit.SECONDS));
      assertTrue(journal.threadName.get().startsWith("execution-exit-confirmation-worker"));

      Thread.sleep(1100);
      assertEquals(1, journal.loads.get());
      assertFalse(journal.concurrent.get());
    } finally {
      journal.unblock();
    }
  }

  @Test
  void oneDatabaseFailureRecoversOnTheNextCycle() throws Exception {
    ObservingJournal journal = new ObservingJournal();
    journal.failOnce = true;
    ExecutionExitConfirmer confirmer = confirmer(journal);
    AtomicReference<Runnable> dispatch = new AtomicReference<>();
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doAnswer(
            invocation -> {
              dispatch.set(invocation.getArgument(0));
              return future;
            })
        .when(timer)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    ExecutorService worker = Executors.newSingleThreadExecutor();

    try (ExecutionExitConfirmationRetry retry =
        new ExecutionExitConfirmationRetry(confirmer, timer, worker)) {
      retry.start();
      dispatch.get().run();
      assertTrue(journal.awaitLoads(1));
      assertTrue(journal.completed.await(1, TimeUnit.SECONDS));
      dispatchUntil(dispatch, journal, 2);
    }
  }

  @Test
  void closeInterruptsHeldReplayAndStopsFutureDispatch() throws Exception {
    ObservingJournal journal = new ObservingJournal();
    journal.block = true;
    ExecutionExitConfirmer confirmer = confirmer(journal);
    AtomicReference<Runnable> dispatch = new AtomicReference<>();
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doAnswer(
            invocation -> {
              dispatch.set(invocation.getArgument(0));
              return future;
            })
        .when(timer)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ExecutionExitConfirmationRetry retry =
        new ExecutionExitConfirmationRetry(confirmer, timer, worker);
    try {
      retry.start();
      dispatch.get().run();
      assertTrue(journal.entered.await(2, TimeUnit.SECONDS));
      retry.close();
      int loadsAtClose = journal.loads.get();
      dispatch.get().run();
      Thread.sleep(50);
      assertEquals(loadsAtClose, journal.loads.get());
    } finally {
      journal.unblock();
      retry.close();
    }
  }

  @Test
  void interruptedAwaitStillAttemptsBothExecutorsAndRestoresInterrupt() throws Exception {
    ObservingJournal journal = new ObservingJournal();
    ExecutionExitConfirmer confirmer = confirmer(journal);
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    ExecutorService worker = mock(ExecutorService.class);
    when(timer.awaitTermination(anyLong(), any(TimeUnit.class)))
        .thenThrow(new InterruptedException("test"));
    when(worker.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

    try (ExecutionExitConfirmationRetry retry =
        new ExecutionExitConfirmationRetry(confirmer, timer, worker)) {
      retry.close();
      verify(timer).awaitTermination(anyLong(), any(TimeUnit.class));
      verify(worker).awaitTermination(anyLong(), any(TimeUnit.class));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static ExecutionExitConfirmer confirmer(ExecutionExitJournal journal) {
    return new ExecutionExitConfirmer(
        new InMemoryBuiltinHarnessStore(), journal, Clock.systemUTC());
  }

  private static void dispatchUntil(
      AtomicReference<Runnable> dispatch, ObservingJournal journal, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (journal.loads.get() < expected && System.nanoTime() < deadline) {
      dispatch.get().run();
      Thread.sleep(10);
    }
    assertTrue(journal.loads.get() >= expected);
  }

  private static final class ObservingJournal implements ExecutionExitJournal {

    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean concurrent = new AtomicBoolean();
    private final AtomicReference<String> threadName = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean block;
    private volatile boolean failOnce;

    @Override
    public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
      return record;
    }

    @Override
    public void delete(UUID executionId) {}

    @Override
    public ExecutionExitJournalSnapshot loadAll() {
      if (active.incrementAndGet() > 1) {
        concurrent.set(true);
      }
      loads.incrementAndGet();
      threadName.set(Thread.currentThread().getName());
      entered.countDown();
      try {
        if (block) {
          try {
            release.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutionExitJournalException("interrupted while replaying");
          }
        }
        if (failOnce) {
          failOnce = false;
          throw new ExecutionExitJournalException("database unavailable");
        }
        return new ExecutionExitJournalSnapshot(List.of(), List.of());
      } finally {
        active.decrementAndGet();
        completed.countDown();
      }
    }

    private void unblock() {
      block = false;
      release.countDown();
    }

    private boolean awaitLoads(int expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
      while (loads.get() < expected && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      return loads.get() >= expected;
    }

    @Override
    public void close() {}
  }
}
