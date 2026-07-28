package fun.fengwk.kkstudio.core.harness.model.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs bounded PostgreSQL Model Invocation recovery scans while workers are enabled. */
@Slf4j
public final class ModelWorkerLifecycle implements SmartLifecycle {

  private static final int PHASE = Integer.MAX_VALUE - 80;

  private final HarnessRuntimeProperties properties;
  private final ModelWorker modelWorker;
  private final ScheduledExecutorService scheduler;
  private final Duration recoveryInterval;
  private final int recoveryBatchSize;

  /** Serializes lifecycle transitions with each durable dispatch during shutdown. */
  private final Object lifecycleMonitor = new Object();

  private volatile boolean running;
  private volatile boolean stopping;
  private volatile ScheduledFuture<?> future;

  public ModelWorkerLifecycle(
      HarnessRuntimeProperties properties,
      ModelWorker modelWorker,
      ScheduledExecutorService scheduler,
      Duration recoveryInterval,
      int recoveryBatchSize) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.modelWorker = Objects.requireNonNull(modelWorker, "modelWorker");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.recoveryInterval = Objects.requireNonNull(recoveryInterval, "recoveryInterval");
    if (recoveryInterval.toMillis() <= 0) {
      throw new IllegalArgumentException(
          "model recovery interval must be at least one millisecond");
    }
    if (recoveryBatchSize <= 0) {
      throw new IllegalArgumentException("model recovery batch size must be positive");
    }
    this.recoveryBatchSize = recoveryBatchSize;
  }

  @Override
  public boolean isAutoStartup() {
    return properties.isWorkersEnabled();
  }

  @Override
  public void start() {
    synchronized (lifecycleMonitor) {
      if (!properties.isWorkersEnabled() || running) {
        return;
      }
      stopping = false;
      running = true;
      try {
        future =
            scheduler.scheduleWithFixedDelay(
                this::scanSafely, 0L, recoveryInterval.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RuntimeException error) {
        running = false;
        future = null;
        log.warn("model recovery schedule failed", error);
        throw error;
      }
    }
  }

  @Override
  public void stop() {
    stopping = true;
    synchronized (lifecycleMonitor) {
      running = false;
      ScheduledFuture<?> current = future;
      future = null;
      if (current != null) {
        current.cancel(false);
      }
      modelWorker.stop();
    }
  }

  @Override
  public void stop(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    ScheduledFuture<?> current = future;
    return running
        && !stopping
        && properties.isWorkersEnabled()
        && current != null
        && !current.isCancelled()
        && !current.isDone();
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  /** Drains at most one configured recovery batch and stops at the first unavailable claim. */
  public int scanOnce() {
    return drain(false);
  }

  private int scanWhileRunning() {
    return drain(true);
  }

  private int drain(boolean requireRunning) {
    int dispatched = 0;
    while (dispatched < recoveryBatchSize && dispatchNext(requireRunning)) {
      dispatched++;
    }
    return dispatched;
  }

  private boolean dispatchNext(boolean requireRunning) {
    synchronized (lifecycleMonitor) {
      if (!properties.isWorkersEnabled() || (requireRunning && (!running || stopping))) {
        return false;
      }
      return modelWorker.dispatchNext();
    }
  }

  private void scanSafely() {
    try {
      scanWhileRunning();
    } catch (RuntimeException error) {
      log.warn("model recovery scan failed", error);
    }
  }
}
