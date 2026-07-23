package fun.fengwk.kkstudio.core.harness.model.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ModelWorkerLifecycleTest {

  @Test
  void schedulesImmediateRecoveryScanWhenEnabled() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    when(worker.dispatchNext()).thenReturn(false);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(), eq(0L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);

    lifecycle.start();

    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler)
        .scheduleWithFixedDelay(callback.capture(), eq(0L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    assertTrue(lifecycle.isRunning());
    assertEquals(Integer.MAX_VALUE - 80, lifecycle.getPhase());
    callback.getValue().run();
    verify(worker).dispatchNext();
  }

  @Test
  void drainsOnlyTheConfiguredBatchAndStopsWhenNoClaimIsAvailable() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    when(worker.dispatchNext()).thenReturn(true, true, true);
    ModelWorkerLifecycle bounded =
        lifecycle(properties, worker, mock(ScheduledExecutorService.class), 2);

    assertEquals(2, bounded.scanOnce());
    verify(worker, times(2)).dispatchNext();

    ModelWorker stoppingWorker = mock(ModelWorker.class);
    when(stoppingWorker.dispatchNext()).thenReturn(true, false, true);
    ModelWorkerLifecycle stopping =
        lifecycle(properties, stoppingWorker, mock(ScheduledExecutorService.class), 5);
    assertEquals(1, stopping.scanOnce());
    verify(stoppingWorker, times(2)).dispatchNext();
  }

  @Test
  void remainsStoppedAndDoesNotScanWhenWorkersAreDisabled() {
    HarnessRuntimeProperties properties = enabledProperties();
    properties.setWorkersEnabled(false);
    ModelWorker worker = mock(ModelWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);

    lifecycle.start();

    assertFalse(lifecycle.isAutoStartup());
    assertFalse(lifecycle.isRunning());
    assertEquals(0, lifecycle.scanOnce());
    verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    verify(worker, never()).dispatchNext();
  }

  @Test
  void isolatesScanFailuresSoLaterScheduledScansContinue() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    when(worker.dispatchNext())
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(false);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);
    lifecycle.start();
    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());

    assertDoesNotThrow(callback.getValue()::run);
    assertDoesNotThrow(callback.getValue()::run);

    verify(worker, times(2)).dispatchNext();
  }

  @Test
  void resetsStateAndPropagatesWhenSchedulerRejectsStart() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenThrow(new RejectedExecutionException("stopped"));
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);

    assertThrows(RejectedExecutionException.class, lifecycle::start);

    assertFalse(lifecycle.isRunning());
    verify(worker, never()).dispatchNext();
  }

  @Test
  void rejectsIntervalsBelowSchedulingPrecisionAndNonPositiveBatches() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelWorkerLifecycle(properties, worker, scheduler, Duration.ofNanos(999_999), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelWorkerLifecycle(properties, worker, scheduler, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelWorkerLifecycle(properties, worker, scheduler, Duration.ofMillis(1), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelWorkerLifecycle(properties, worker, scheduler, Duration.ofMillis(1), -1));
  }

  @Test
  void reportsStoppedWhenScheduledFutureIsDone() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    when(future.isDone()).thenReturn(true);
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);

    lifecycle.start();

    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopWaitsForScheduledDispatchAndPreventsTheNextDispatch() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    CountDownLatch dispatchStarted = new CountDownLatch(1);
    CountDownLatch releaseDispatch = new CountDownLatch(1);
    CountDownLatch workerStopped = new CountDownLatch(1);
    when(worker.dispatchNext())
        .thenAnswer(
            ignored -> {
              dispatchStarted.countDown();
              await(releaseDispatch);
              return true;
            });
    doAnswer(
            ignored -> {
              workerStopped.countDown();
              return null;
            })
        .when(worker)
        .stop();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);
    lifecycle.start();
    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());
    Thread scanThread = new Thread(callback.getValue(), "scheduled-model-recovery-test");
    scanThread.start();
    await(dispatchStarted);
    Thread stopThread = new Thread(lifecycle::stop, "model-worker-stop-test");
    stopThread.start();
    awaitStopped(lifecycle);

    assertEquals(1L, workerStopped.getCount(), "stop must wait for the active dispatch");
    releaseDispatch.countDown();
    await(workerStopped);
    join(scanThread);
    join(stopThread);

    verify(worker, times(1)).dispatchNext();
    verify(worker).stop();
  }

  @Test
  void cancelsPollingThenStopsWorkerAndInvokesStopCallback() {
    HarnessRuntimeProperties properties = enabledProperties();
    ModelWorker worker = mock(ModelWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ModelWorkerLifecycle lifecycle = lifecycle(properties, worker, scheduler, 100);
    lifecycle.start();
    AtomicBoolean callbackCalled = new AtomicBoolean();

    lifecycle.stop(() -> callbackCalled.set(true));

    InOrder order = inOrder(future, worker);
    order.verify(future).cancel(false);
    order.verify(worker).stop();
    assertTrue(callbackCalled.get());
    assertFalse(lifecycle.isRunning());
  }

  private static HarnessRuntimeProperties enabledProperties() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(true);
    return properties;
  }

  private static ModelWorkerLifecycle lifecycle(
      HarnessRuntimeProperties properties,
      ModelWorker worker,
      ScheduledExecutorService scheduler,
      int batchSize) {
    return new ModelWorkerLifecycle(
        properties, worker, scheduler, Duration.ofSeconds(1), batchSize);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(2, TimeUnit.SECONDS), "timed out waiting for test synchronization");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail("interrupted while waiting for test synchronization", error);
    }
  }

  private static void awaitStopped(ModelWorkerLifecycle lifecycle) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (lifecycle.isRunning()) {
      if (System.nanoTime() >= deadline) {
        fail("timed out waiting for lifecycle stop request");
      }
      Thread.onSpinWait();
    }
  }

  private static void join(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(2));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail("interrupted while joining test thread", error);
    }
    assertFalse(thread.isAlive(), "test thread did not finish");
  }

  @SuppressWarnings("unchecked")
  private static ScheduledFuture<?> scheduledFuture() {
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    when(future.isCancelled()).thenReturn(false);
    when(future.isDone()).thenReturn(false);
    return future;
  }
}
