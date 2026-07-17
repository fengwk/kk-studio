package fun.fengwk.kkstudio.core.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Lifecycle contracts keep disposable scheduler failures outside the durable gateway state machine.
 */
class EnvironmentDaemonGatewayLifecycleTest {

  /** The scheduled callback delegates one durable tick and cancels cleanly. */
  @Test
  void schedulesSafePollAndStops() {
    EnvironmentDaemonGateway gateway = mock(EnvironmentDaemonGateway.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    HarnessRuntimeProperties properties = properties(true);
    EnvironmentDaemonGatewayLifecycle lifecycle =
        new EnvironmentDaemonGatewayLifecycle(gateway, properties, scheduler);

    lifecycle.start();
    ArgumentCaptor<Runnable> poll = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler)
        .scheduleWithFixedDelay(poll.capture(), eq(0L), eq(100L), eq(TimeUnit.MILLISECONDS));
    poll.getValue().run();
    verify(gateway).pollOnce();

    AtomicBoolean callback = new AtomicBoolean();
    lifecycle.stop(() -> callback.set(true));
    verify(future).cancel(false);
    assertTrue(callback.get());
    assertFalse(lifecycle.isRunning());
    assertTrue(lifecycle.isAutoStartup());
  }

  /**
   * Scheduler rejection restores lifecycle state and preserves the workers-enabled startup switch.
   */
  @Test
  void rollsBackRejectedStartAndHonorsDisabledWorkers() {
    EnvironmentDaemonGateway gateway = mock(EnvironmentDaemonGateway.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(
            any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenThrow(new IllegalStateException("rejected"));
    EnvironmentDaemonGatewayLifecycle lifecycle =
        new EnvironmentDaemonGatewayLifecycle(gateway, properties(false), scheduler);

    assertFalse(lifecycle.isAutoStartup());
    assertThrows(IllegalStateException.class, lifecycle::start);
    assertFalse(lifecycle.isRunning());
  }

  private static HarnessRuntimeProperties properties(boolean workersEnabled) {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(workersEnabled);
    properties.setPollInterval(Duration.ofMillis(100));
    return properties;
  }
}
