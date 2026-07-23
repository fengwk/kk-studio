package fun.fengwk.kkstudio.harness.runtime.model.worker;

import java.time.Duration;
import java.util.Objects;

/** ModelWorker 的部署级 lease 与真实 activity 持久化 cadence。 */
public record ModelWorkerConfig(
    Duration workerLeaseDuration, Duration heartbeatInterval, Duration activityFlushInterval) {

  public ModelWorkerConfig {
    workerLeaseDuration = requireMillisPositive(workerLeaseDuration, "workerLeaseDuration");
    heartbeatInterval = requireMillisPositive(heartbeatInterval, "heartbeatInterval");
    activityFlushInterval = requireMillisPositive(activityFlushInterval, "activityFlushInterval");
    if (heartbeatInterval.compareTo(workerLeaseDuration) >= 0) {
      throw new IllegalArgumentException("heartbeatInterval must be less than workerLeaseDuration");
    }
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
    return value;
  }
}
