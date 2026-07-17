package fun.fengwk.kkstudio.core.harness.run.worker;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker.ClaimedTurn;
import fun.fengwk.kkstudio.harness.runtime.run.RunWorkerConfig;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.SmartLifecycle;

/** Process lifecycle for one database-claimed Agent turn at a time. */
public final class AgentTurnWorkerLifecycle implements SmartLifecycle {

  private static final System.Logger LOGGER =
      System.getLogger(AgentTurnWorkerLifecycle.class.getName());

  private final AgentTurnWorker worker;
  private final String workerId;
  private final Duration pollInterval;
  private final Duration heartbeatInterval;
  private final ScheduledExecutorService scheduler;
  private final boolean autoStartup;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicReference<ClaimedTurn> active = new AtomicReference<>();
  private volatile ScheduledFuture<?> pollFuture;
  private volatile ScheduledFuture<?> heartbeatFuture;

  public AgentTurnWorkerLifecycle(
      AgentTurnWorker worker,
      RunWorkerConfig config,
      HarnessRuntimeProperties properties,
      ScheduledExecutorService scheduler) {
    this.worker = Objects.requireNonNull(worker, "worker");
    config = Objects.requireNonNull(config, "config");
    properties = Objects.requireNonNull(properties, "properties");
    this.workerId = properties.requireWorkerId() + "-turn";
    this.pollInterval = properties.requirePollInterval();
    this.heartbeatInterval = config.heartbeatInterval();
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.autoStartup = properties.isWorkersEnabled();
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      pollFuture =
          scheduler.scheduleWithFixedDelay(
              this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
      heartbeatFuture =
          scheduler.scheduleAtFixedRate(
              this::heartbeatSafely,
              heartbeatInterval.toMillis(),
              heartbeatInterval.toMillis(),
              TimeUnit.MILLISECONDS);
    } catch (RuntimeException error) {
      running.set(false);
      cancelFuture(pollFuture);
      cancelFuture(heartbeatFuture);
      throw error;
    }
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    cancelFuture(pollFuture);
    cancelFuture(heartbeatFuture);
    ClaimedTurn turn = active.getAndSet(null);
    if (turn != null) {
      cancel(turn);
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  void pollOnce() {
    if (!running.get()) {
      return;
    }
    // Release a normally completed turn immediately so the next claim does not wait for the
    // heartbeat lease-loss cycle.
    if (!releaseIfDone()) {
      return;
    }
    Optional<ClaimedTurn> claimed = worker.executeNext(workerId);
    if (claimed.isEmpty()) {
      return;
    }
    ClaimedTurn turn = claimed.orElseThrow();
    if (!running.get() || !active.compareAndSet(null, turn)) {
      cancel(turn);
    }
  }

  void heartbeatOnce() {
    if (!running.get()) {
      return;
    }
    ClaimedTurn turn = active.get();
    if (turn == null) {
      return;
    }
    if (turn.handle().isDone()) {
      active.compareAndSet(turn, null);
      return;
    }
    boolean owned;
    try {
      owned = worker.heartbeat(turn);
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Harness turn heartbeat failed", error);
      owned = false;
    }
    if (!owned) {
      try {
        cancel(turn);
      } finally {
        active.compareAndSet(turn, null);
      }
    }
  }

  /**
   * @return {@code true} when the lifecycle is idle and may claim the next turn
   */
  private boolean releaseIfDone() {
    ClaimedTurn turn = active.get();
    if (turn == null) {
      return true;
    }
    if (!turn.handle().isDone()) {
      return false;
    }
    return active.compareAndSet(turn, null);
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Harness turn worker poll failed", error);
    }
  }

  private void heartbeatSafely() {
    try {
      heartbeatOnce();
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Harness turn worker heartbeat task failed", error);
    }
  }

  private void cancel(ClaimedTurn turn) {
    try {
      turn.handle().cancel();
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Harness turn cancellation failed", error);
    }
  }

  private static void cancelFuture(ScheduledFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }
}
