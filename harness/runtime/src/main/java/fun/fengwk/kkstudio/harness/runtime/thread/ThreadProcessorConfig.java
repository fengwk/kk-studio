package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Duration;
import java.util.Objects;

/** ThreadProcessor 运行参数。 */
public record ThreadProcessorConfig(
    Duration processorLease,
    Duration deltaFlushInterval,
    int deltaBatchBytes,
    Duration modelCallTimeout,
    int maxExecutorConcurrency) {

  public ThreadProcessorConfig {
    processorLease = Objects.requireNonNull(processorLease, "processorLease");
    deltaFlushInterval = Objects.requireNonNull(deltaFlushInterval, "deltaFlushInterval");
    modelCallTimeout = Objects.requireNonNull(modelCallTimeout, "modelCallTimeout");
    if (deltaBatchBytes <= 0 || maxExecutorConcurrency <= 0) {
      throw new IllegalArgumentException("numeric processor config must be positive");
    }
    if (processorLease.isNegative()
        || processorLease.isZero()
        || deltaFlushInterval.isNegative()
        || modelCallTimeout.isNegative()
        || modelCallTimeout.isZero()) {
      throw new IllegalArgumentException("durations must be positive where required");
    }
  }

  public static ThreadProcessorConfig defaults() {
    return new ThreadProcessorConfig(
        Duration.ofSeconds(30), Duration.ofMillis(50), 16 * 1024, Duration.ofMinutes(5), 8);
  }
}
