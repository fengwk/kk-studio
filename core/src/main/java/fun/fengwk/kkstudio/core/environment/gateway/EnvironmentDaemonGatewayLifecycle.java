package fun.fengwk.kkstudio.core.environment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls ready Environment connections for durable dispatch, cancellation, and lease recovery. */
@Slf4j
public final class EnvironmentDaemonGatewayLifecycle implements SmartLifecycle {

  private final Runnable pollAction;
  private final Duration pollInterval;
  private final ScheduledExecutorService scheduler;
  private final boolean autoStartup;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile ScheduledFuture<?> pollFuture;

  public EnvironmentDaemonGatewayLifecycle(
      EnvironmentDaemonGateway gateway,
      HarnessRuntimeProperties runtimeProperties,
      ScheduledExecutorService scheduler) {
    this(Objects.requireNonNull(gateway, "gateway")::pollOnce, runtimeProperties, scheduler);
  }

  EnvironmentDaemonGatewayLifecycle(
      Runnable pollAction,
      HarnessRuntimeProperties runtimeProperties,
      ScheduledExecutorService scheduler) {
    this.pollAction = Objects.requireNonNull(pollAction, "pollAction");
    runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties");
    this.pollInterval = runtimeProperties.requirePollInterval();
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.autoStartup = runtimeProperties.isWorkersEnabled();
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
    return Integer.MAX_VALUE - 90;
  }

  private void pollSafely() {
    try {
      pollAction.run();
    } catch (RuntimeException error) {
      log.warn("Environment daemon gateway poll failed", error);
    }
  }
}
