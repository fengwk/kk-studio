package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

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
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
