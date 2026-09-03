package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Harness Work Dispatcher 的部署级容量与调度配置。
 *
 * <p>这些值是进程启动与容器部署边界，不属于 SystemSettings、DTO 或前端配置。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.dispatcher")
public class HarnessDispatcherProperties {

  public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  public static final Duration DEFAULT_REJECTION_DELAY = Duration.ofSeconds(1);
  public static final int DEFAULT_MAX_DISPATCH_TASKS = 64;
  public static final int DEFAULT_WORKER_CONCURRENCY = 16;
  public static final int DEFAULT_WORKER_QUEUE_CAPACITY = 64;

  private Duration leaseDuration = DEFAULT_LEASE_DURATION;
  private Duration pollInterval = DEFAULT_POLL_INTERVAL;
  private Duration rejectionDelay = DEFAULT_REJECTION_DELAY;
  private int maxDispatchTasks = DEFAULT_MAX_DISPATCH_TASKS;
  private Worker worker = new Worker();

  @Data
  public static class Worker {
    private int concurrency = DEFAULT_WORKER_CONCURRENCY;
    private int queueCapacity = DEFAULT_WORKER_QUEUE_CAPACITY;

    public int getConcurrency() {
      if (concurrency < 1) {
        throw new IllegalArgumentException(
            "kk-studio.harness.dispatcher.worker.concurrency must be at least 1");
      }
      return concurrency;
    }

    public void setConcurrency(int concurrency) {
      if (concurrency < 1) {
        throw new IllegalArgumentException(
            "kk-studio.harness.dispatcher.worker.concurrency must be at least 1");
      }
      this.concurrency = concurrency;
    }

    public int getQueueCapacity() {
      if (queueCapacity < 1) {
        throw new IllegalArgumentException(
            "kk-studio.harness.dispatcher.worker.queue-capacity must be at least 1");
      }
      return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
      if (queueCapacity < 1) {
        throw new IllegalArgumentException(
            "kk-studio.harness.dispatcher.worker.queue-capacity must be at least 1");
      }
      this.queueCapacity = queueCapacity;
    }
  }

  public Duration getLeaseDuration() {
    if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.lease-duration must be positive");
    }
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.lease-duration must be positive");
    }
    this.leaseDuration = leaseDuration;
  }

  public Duration getPollInterval() {
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.poll-interval must be positive");
    }
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.poll-interval must be positive");
    }
    this.pollInterval = pollInterval;
  }

  public Duration getRejectionDelay() {
    if (rejectionDelay == null || rejectionDelay.isNegative() || rejectionDelay.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.rejection-delay must be positive");
    }
    return rejectionDelay;
  }

  public void setRejectionDelay(Duration rejectionDelay) {
    if (rejectionDelay == null || rejectionDelay.isNegative() || rejectionDelay.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.rejection-delay must be positive");
    }
    this.rejectionDelay = rejectionDelay;
  }

  public int getMaxDispatchTasks() {
    if (maxDispatchTasks < 1) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.max-dispatch-tasks must be at least 1");
    }
    return maxDispatchTasks;
  }

  public void setMaxDispatchTasks(int maxDispatchTasks) {
    if (maxDispatchTasks < 1) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.max-dispatch-tasks must be at least 1");
    }
    this.maxDispatchTasks = maxDispatchTasks;
  }
}
