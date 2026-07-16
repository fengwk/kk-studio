package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CloudToolWorkerLifecycleTest {

  /**
   * Polling delegates to the existing state machine only when no process-local execution is active.
   */
  @Test
  void dispatchesAtMostOneIdleCloudToolPerTickAndStopsClearly() {
    CloudToolWorker worker = mock(CloudToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
    doReturn(pollFuture)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    when(worker.hasActiveExecution()).thenReturn(true, false);
    when(worker.executeNext("worker-test-cloud-tool")).thenReturn(Optional.empty());
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkerId("worker-test");
    properties.setPollInterval(Duration.ofSeconds(1));
    properties.setWorkersEnabled(true);
    CloudToolWorkerLifecycle lifecycle =
        new CloudToolWorkerLifecycle(worker, properties, scheduler);

    lifecycle.start();
    lifecycle.pollOnce();
    lifecycle.pollOnce();

    verify(worker, times(1)).executeNext("worker-test-cloud-tool");
    lifecycle.stop();
    verify(worker).stop();
    verify(pollFuture).cancel(false);
    assertFalse(lifecycle.isRunning());
  }

  /** A rejected scheduler start leaves no running lifecycle and stop callback still completes. */
  @Test
  void rollsBackRejectedStartAndInvokesStopCallback() {
    CloudToolWorker worker = mock(CloudToolWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(
            any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenThrow(new IllegalStateException("rejected"));
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkerId("worker-test");
    properties.setPollInterval(Duration.ofSeconds(1));
    CloudToolWorkerLifecycle lifecycle =
        new CloudToolWorkerLifecycle(worker, properties, scheduler);

    assertThrows(IllegalStateException.class, lifecycle::start);
    assertFalse(lifecycle.isRunning());
    AtomicBoolean callback = new AtomicBoolean();
    lifecycle.stop(() -> callback.set(true));
    assertTrue(callback.get());
  }
}
