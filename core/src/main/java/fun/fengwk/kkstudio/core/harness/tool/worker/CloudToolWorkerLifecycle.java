package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process lifecycle that polls at most one durable Cloud/Control tool when none is active. */
public final class CloudToolWorkerLifecycle implements SmartLifecycle {

  private static final System.Logger LOGGER =
      System.getLogger(CloudToolWorkerLifecycle.class.getName());

  private final CloudToolWorker worker;
  private final String workerId;
  private final Duration pollInterval;
  private final ScheduledExecutorService scheduler;
  private final boolean autoStartup;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile ScheduledFuture<?> pollFuture;

  public CloudToolWorkerLifecycle(
      CloudToolWorker worker,
      HarnessRuntimeProperties properties,
      ScheduledExecutorService scheduler) {
    this.worker = Objects.requireNonNull(worker, "worker");
    properties = Objects.requireNonNull(properties, "properties");
    this.workerId = properties.requireWorkerId() + "-cloud-tool";
    this.pollInterval = properties.requirePollInterval();
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
    } catch (RuntimeException error) {
      running.set(false);
      ScheduledFuture<?> future = pollFuture;
      if (future != null) {
        future.cancel(false);
      }
      throw error;
    }
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    ScheduledFuture<?> future = pollFuture;
    if (future != null) {
      future.cancel(false);
    }
    worker.stop();
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
    if (!running.get() || worker.hasActiveExecution()) {
      return;
    }
    worker.executeNext(workerId);
    if (!running.get()) {
      worker.stop();
    }
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Harness Cloud tool worker poll failed", error);
    }
  }
}
