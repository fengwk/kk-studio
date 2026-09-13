package fun.fengwk.kkstudio.platform.project.controller;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

/** Issue Controller 的容量、租约与调度控制部署级配置。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.project.controller")
public class IssueControllerProperties {

  public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  public static final Duration DEFAULT_REJECTION_DELAY = Duration.ofSeconds(1);
  public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);
  public static final Duration DEFAULT_ACTIVE_DELAY = Duration.ofSeconds(1);
  public static final Duration DEFAULT_BLOCKED_DELAY = Duration.ofSeconds(60);
  public static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(30);
  public static final int DEFAULT_MAX_CONTINUATIONS = 10;
  public static final int DEFAULT_MAX_DISPATCH_TASKS = 64;
  public static final int DEFAULT_WORKER_CONCURRENCY = 8;
  public static final int DEFAULT_WORKER_QUEUE_CAPACITY = 64;

  private Duration leaseDuration = DEFAULT_LEASE_DURATION;
  private Duration pollInterval = DEFAULT_POLL_INTERVAL;
  private Duration rejectionDelay = DEFAULT_REJECTION_DELAY;
  private Duration retryDelay = DEFAULT_RETRY_DELAY;
  private Duration activeDelay = DEFAULT_ACTIVE_DELAY;
  private Duration blockedDelay = DEFAULT_BLOCKED_DELAY;
  private Duration runTimeout = DEFAULT_RUN_TIMEOUT;
  private int maxContinuations = DEFAULT_MAX_CONTINUATIONS;
  private int maxDispatchTasks = DEFAULT_MAX_DISPATCH_TASKS;
  private Worker worker = new Worker();

  @Data
  public static class Worker {
    private int concurrency = DEFAULT_WORKER_CONCURRENCY;
    private int queueCapacity = DEFAULT_WORKER_QUEUE_CAPACITY;

    public int getConcurrency() {
      if (concurrency < 1) {
        throw new IllegalArgumentException(
            "kk-studio.project.controller.worker.concurrency must be at least 1");
      }
      return concurrency;
    }

    public void setConcurrency(int concurrency) {
      if (concurrency < 1) {
        throw new IllegalArgumentException(
            "kk-studio.project.controller.worker.concurrency must be at least 1");
      }
      this.concurrency = concurrency;
    }

    public int getQueueCapacity() {
      if (queueCapacity < 1) {
        throw new IllegalArgumentException(
            "kk-studio.project.controller.worker.queue-capacity must be at least 1");
      }
      return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
      if (queueCapacity < 1) {
        throw new IllegalArgumentException(
            "kk-studio.project.controller.worker.queue-capacity must be at least 1");
      }
      this.queueCapacity = queueCapacity;
    }
  }

  public Duration getLeaseDuration() {
    return validateDuration(leaseDuration, "kk-studio.project.controller.lease-duration");
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration =
        validateDuration(leaseDuration, "kk-studio.project.controller.lease-duration");
  }

  public Duration getPollInterval() {
    return validateDuration(pollInterval, "kk-studio.project.controller.poll-interval");
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval =
        validateDuration(pollInterval, "kk-studio.project.controller.poll-interval");
  }

  public Duration getRejectionDelay() {
    return validateDuration(rejectionDelay, "kk-studio.project.controller.rejection-delay");
  }

  public void setRejectionDelay(Duration rejectionDelay) {
    this.rejectionDelay =
        validateDuration(rejectionDelay, "kk-studio.project.controller.rejection-delay");
  }

  public Duration getRetryDelay() {
    return validateDuration(retryDelay, "kk-studio.project.controller.retry-delay");
  }

  public void setRetryDelay(Duration retryDelay) {
    this.retryDelay = validateDuration(retryDelay, "kk-studio.project.controller.retry-delay");
  }

  public Duration getActiveDelay() {
    return validateDuration(activeDelay, "kk-studio.project.controller.active-delay");
  }

  public void setActiveDelay(Duration activeDelay) {
    this.activeDelay = validateDuration(activeDelay, "kk-studio.project.controller.active-delay");
  }

  public Duration getBlockedDelay() {
    return validateDuration(blockedDelay, "kk-studio.project.controller.blocked-delay");
  }

  public void setBlockedDelay(Duration blockedDelay) {
    this.blockedDelay =
        validateDuration(blockedDelay, "kk-studio.project.controller.blocked-delay");
  }

  public Duration getRunTimeout() {
    return validateDuration(runTimeout, "kk-studio.project.controller.run-timeout");
  }

  public void setRunTimeout(Duration runTimeout) {
    this.runTimeout = validateDuration(runTimeout, "kk-studio.project.controller.run-timeout");
  }

  public int getMaxContinuations() {
    if (maxContinuations < 0) {
      throw new IllegalArgumentException(
          "kk-studio.project.controller.max-continuations must be non-negative");
    }
    return maxContinuations;
  }

  public void setMaxContinuations(int maxContinuations) {
    if (maxContinuations < 0) {
      throw new IllegalArgumentException(
          "kk-studio.project.controller.max-continuations must be non-negative");
    }
    this.maxContinuations = maxContinuations;
  }

  public int getMaxDispatchTasks() {
    if (maxDispatchTasks <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.project.controller.max-dispatch-tasks must be positive");
    }
    return maxDispatchTasks;
  }

  public void setMaxDispatchTasks(int maxDispatchTasks) {
    if (maxDispatchTasks <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.project.controller.max-dispatch-tasks must be positive");
    }
    this.maxDispatchTasks = maxDispatchTasks;
  }

  private static Duration validateDuration(Duration duration, String propertyName) {
    if (duration == null) {
      throw new IllegalArgumentException(propertyName + " must not be null");
    }
    return HarnessStoreTime.requireWholeMillisecondDuration(duration, propertyName);
  }
}
