package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs bounded PostgreSQL ToolInvocation recovery for PLATFORM and READY ENVIRONMENT work. */
public final class ToolWorkerLifecycle implements SmartLifecycle {
  private static final System.Logger LOGGER = System.getLogger(ToolWorkerLifecycle.class.getName());
  private static final int PHASE = Integer.MAX_VALUE - 70;

  private final HarnessRuntimeProperties properties;
  private final ToolWorker worker;
  private final LiveEnvironmentRegistry environmentRegistry;
  private final ScheduledExecutorService scheduler;
  private final Duration interval;
  private final int batchSize;
  private final Object monitor = new Object();
  private volatile boolean running;
  private volatile ScheduledFuture<?> future;

  public ToolWorkerLifecycle(
      HarnessRuntimeProperties properties,
      ToolWorker worker,
      ScheduledExecutorService scheduler,
      Duration interval,
      int batchSize) {
    this(properties, worker, null, scheduler, interval, batchSize);
  }

  public ToolWorkerLifecycle(
      HarnessRuntimeProperties properties,
      ToolWorker worker,
      LiveEnvironmentRegistry environmentRegistry,
      ScheduledExecutorService scheduler,
      Duration interval,
      int batchSize) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.environmentRegistry = environmentRegistry;
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.interval = Objects.requireNonNull(interval, "interval");
    if (interval.toMillis() <= 0 || batchSize <= 0) {
      throw new IllegalArgumentException("tool recovery interval and batch size must be positive");
    }
    this.batchSize = batchSize;
  }

  @Override
  public boolean isAutoStartup() {
    return properties.isWorkersEnabled();
  }

  @Override
  public void start() {
    synchronized (monitor) {
      if (running || !properties.isWorkersEnabled()) {
        return;
      }
      running = true;
      try {
        future =
            scheduler.scheduleWithFixedDelay(
                this::scanSafely, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RuntimeException error) {
        running = false;
        throw error;
      }
    }
  }

  @Override
  public void stop() {
    synchronized (monitor) {
      running = false;
      if (future != null) {
        future.cancel(false);
        future = null;
      }
      worker.stop();
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
    ScheduledFuture<?> current = future;
    return running && current != null && !current.isCancelled() && !current.isDone();
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  public int scanOnce() {
    int dispatched = 0;
    synchronized (monitor) {
      while (properties.isWorkersEnabled() && dispatched < batchSize) {
        boolean progressed = false;
        if (worker.recoverNextExpired(ToolExecutionLocation.PLATFORM)) {
          progressed = true;
          dispatched++;
        }
        if (dispatched < batchSize
            && worker.recoverNextExpired(ToolExecutionLocation.ENVIRONMENT)) {
          progressed = true;
          dispatched++;
        }
        if (dispatched < batchSize && worker.dispatchNext()) {
          progressed = true;
          dispatched++;
        }
        if (environmentRegistry != null && dispatched < batchSize) {
          for (var environment : environmentRegistry.listReady()) {
            if (dispatched >= batchSize) {
              break;
            }
            if (worker.dispatchNext(
                ToolExecutionLocation.ENVIRONMENT, environment.environmentName())) {
              progressed = true;
              dispatched++;
            }
          }
        }
        if (!progressed) {
          break;
        }
      }
    }
    return dispatched;
  }

  private void scanSafely() {
    try {
      scanOnce();
    } catch (RuntimeException error) {
      LOGGER.log(System.Logger.Level.WARNING, "tool recovery scan failed", error);
    }
  }
}
