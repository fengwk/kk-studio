package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.PlatformToolWorker;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class ToolWorkerLifecycleTest {

  @Test
  void validatesRecoveryCadenceAndBatchSize() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofSeconds(1), 0));
  }

  @Test
  void disabledWorkersDoNotScheduleOrScan() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(false);
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ToolWorkerLifecycle lifecycle =
        new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofSeconds(1), 2);

    lifecycle.start();

    assertFalse(lifecycle.isAutoStartup());
    assertFalse(lifecycle.isRunning());
    assertEquals(0, lifecycle.scanOnce());
    verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    verify(worker, never()).dispatchNext();
  }

  @Test
  void startIsIdempotentAndStopCancelsScheduleAndAbandonsLocalExecution() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> future = mock(ScheduledFuture.class);
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(100L), eq(TimeUnit.MILLISECONDS));
    when(future.isCancelled()).thenReturn(false);
    when(future.isDone()).thenReturn(false);
    ToolWorkerLifecycle lifecycle =
        new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofMillis(100), 2);

    lifecycle.start();
    lifecycle.start();

    assertTrue(lifecycle.isAutoStartup());
    assertTrue(lifecycle.isRunning());
    assertEquals(Integer.MAX_VALUE - 70, lifecycle.getPhase());
    verify(scheduler, times(1))
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(100L), eq(TimeUnit.MILLISECONDS));

    lifecycle.stop();
    assertFalse(lifecycle.isRunning());
    verify(future).cancel(false);
    verify(worker).stop();
  }

  @Test
  void scanHonorsBatchBoundAndStopsAtFirstEmptyDispatch() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ToolWorkerLifecycle lifecycle =
        new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofSeconds(1), 2);
    when(worker.dispatchNext()).thenReturn(true, true, true);

    assertEquals(2, lifecycle.scanOnce());
    verify(worker, times(2)).dispatchNext();

    PlatformToolWorker emptyWorker = mock(PlatformToolWorker.class);
    ToolWorkerLifecycle empty =
        new ToolWorkerLifecycle(properties, emptyWorker, scheduler, Duration.ofSeconds(1), 3);
    when(emptyWorker.dispatchNext()).thenReturn(true, false, true);
    assertEquals(1, empty.scanOnce());
    verify(emptyWorker, times(2)).dispatchNext();
  }

  @Test
  void schedulerRejectionRestoresStoppedState() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenThrow(new RejectedExecutionException("saturated"));
    ToolWorkerLifecycle lifecycle =
        new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofSeconds(1), 1);

    assertThrows(RejectedExecutionException.class, lifecycle::start);
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void scheduledScanIsolatesWorkerFailureAndStopCallbackAlwaysRuns() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    PlatformToolWorker worker = mock(PlatformToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> future = mock(ScheduledFuture.class);
    ArgumentCaptor<Runnable> scan = ArgumentCaptor.forClass(Runnable.class);
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(scan.capture(), eq(0L), eq(100L), eq(TimeUnit.MILLISECONDS));
    when(worker.dispatchNext()).thenThrow(new IllegalStateException("database unavailable"));
    ToolWorkerLifecycle lifecycle =
        new ToolWorkerLifecycle(properties, worker, scheduler, Duration.ofMillis(100), 1);
    lifecycle.start();

    scan.getValue().run();

    boolean[] callback = {false};
    lifecycle.stop(() -> callback[0] = true);
    assertTrue(callback[0]);
  }
}
