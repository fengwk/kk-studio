package fun.fengwk.kkstudio.core.harness.run.worker;

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
import fun.fengwk.kkstudio.harness.agent.AgentTurnHandle;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker.ClaimedTurn;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.run.RunWorkerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentTurnWorkerLifecycleTest {

  /**
   * A tick claims only when idle; failed durable heartbeat cancels and removes the active handle.
   */
  @Test
  void claimsOneIdleTurnAndDropsItOnHeartbeatFailure() {
    AgentTurnWorker worker = mock(AgentTurnWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
    doReturn(pollFuture)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    doReturn(heartbeatFuture)
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
    RecordingHandle handle = new RecordingHandle();
    ClaimedTurn turn = new ClaimedTurn(run(), handle);
    when(worker.executeNext("worker-test-turn"))
        .thenReturn(Optional.of(turn))
        .thenReturn(Optional.empty());
    when(worker.heartbeat(turn)).thenReturn(false);
    AgentTurnWorkerLifecycle lifecycle = lifecycle(worker, scheduler);

    lifecycle.start();
    lifecycle.pollOnce();
    lifecycle.pollOnce();
    verify(worker, times(1)).executeNext("worker-test-turn");

    lifecycle.heartbeatOnce();
    assertTrue(handle.cancelled);
    lifecycle.pollOnce();
    verify(worker, times(2)).executeNext("worker-test-turn");

    lifecycle.stop();
    assertFalse(lifecycle.isRunning());
    verify(pollFuture).cancel(false);
    verify(heartbeatFuture).cancel(false);
  }

  /** Stopping an active lifecycle cancels its process-local provider handle deterministically. */
  @Test
  void cancelsActiveTurnOnStop() {
    AgentTurnWorker worker = mock(AgentTurnWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    doReturn(mock(ScheduledFuture.class))
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    doReturn(mock(ScheduledFuture.class))
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
    RecordingHandle handle = new RecordingHandle();
    when(worker.executeNext("worker-test-turn"))
        .thenReturn(Optional.of(new ClaimedTurn(run(), handle)));
    AgentTurnWorkerLifecycle lifecycle = lifecycle(worker, scheduler);

    lifecycle.start();
    lifecycle.pollOnce();
    lifecycle.stop();

    assertTrue(handle.cancelled);
    assertFalse(lifecycle.isRunning());
  }

  /** Successful heartbeat keeps ownership; a database exception is treated as loss and removed. */
  @Test
  void keepsOwnedTurnAndDropsItWhenHeartbeatThrows() {
    AgentTurnWorker worker = mock(AgentTurnWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    doReturn(mock(ScheduledFuture.class))
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    doReturn(mock(ScheduledFuture.class))
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
    RecordingHandle handle = new RecordingHandle();
    ClaimedTurn turn = new ClaimedTurn(run(), handle);
    when(worker.executeNext("worker-test-turn"))
        .thenReturn(Optional.of(turn))
        .thenReturn(Optional.empty());
    when(worker.heartbeat(turn)).thenReturn(true).thenThrow(new IllegalStateException("db"));
    AgentTurnWorkerLifecycle lifecycle = lifecycle(worker, scheduler);

    lifecycle.start();
    lifecycle.pollOnce();
    lifecycle.heartbeatOnce();
    lifecycle.pollOnce();
    verify(worker, times(1)).executeNext("worker-test-turn");

    lifecycle.heartbeatOnce();
    assertTrue(handle.cancelled);
    lifecycle.pollOnce();
    verify(worker, times(2)).executeNext("worker-test-turn");
    lifecycle.stop();
  }

  /** Partial scheduler startup is rolled back and asynchronous stop always invokes its callback. */
  @Test
  void rollsBackSchedulerStartFailureAndInvokesStopCallback() {
    AgentTurnWorker worker = mock(AgentTurnWorker.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
    doReturn(pollFuture)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS));
    when(scheduler.scheduleAtFixedRate(
            any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenThrow(new IllegalStateException("rejected"));
    AgentTurnWorkerLifecycle lifecycle = lifecycle(worker, scheduler);

    assertThrows(IllegalStateException.class, lifecycle::start);
    assertFalse(lifecycle.isRunning());
    verify(pollFuture).cancel(false);
    AtomicBoolean callback = new AtomicBoolean();
    lifecycle.stop(() -> callback.set(true));
    assertTrue(callback.get());
  }

  private static AgentTurnWorkerLifecycle lifecycle(
      AgentTurnWorker worker, ScheduledExecutorService scheduler) {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkerId("worker-test");
    properties.setPollInterval(Duration.ofSeconds(1));
    properties.setWorkersEnabled(true);
    return new AgentTurnWorkerLifecycle(worker, RunWorkerConfig.DEFAULT, properties, scheduler);
  }

  private static AgentRun run() {
    Instant now = Instant.parse("2026-07-16T00:00:00Z");
    return new AgentRun(
        1L,
        2L,
        3L,
        RunStatus.RUNNING,
        0,
        1,
        0,
        "worker-test-turn",
        now.plusSeconds(30),
        now,
        null,
        now,
        now,
        null,
        now);
  }

  private static final class RecordingHandle implements AgentTurnHandle {
    private boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }
}
