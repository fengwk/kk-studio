package fun.fengwk.kkstudio.harness.runtime.spring.dispatch;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.Objects;

/**
 * HarnessWorkDispatcher 的部署级配置。
 *
 * <p>THREAD / MODEL / TOOL 各自独立的 {@code leaseDuration} 决定 claim 有效期；{@code periodicPollInterval} 是
 * fixed-delay 周期 poll 的间隔；{@code executorRejectionDelay} 是 worker executor 拒绝 handoff task 后仍 owned
 * claim 的归还重排延迟（正整毫秒）。所有 Duration 必须为正的整毫秒，与 HarnessStore 的毫秒精度时间边界一致。
 *
 * <p>{@code maxDispatchTasks} 明确只约束 dispatcher 本地 queued/running 的 processor handoff task 总数（drain
 * 在达到上限后停止 claim）；它绝不约束异步 Model / Tool execution 的并发总数 —— 那些由各 Processor 及其注入的 Gateway / executor
 * 自行控制。
 */
public record HarnessWorkDispatcherConfig(
    Duration threadLeaseDuration,
    Duration modelLeaseDuration,
    Duration toolLeaseDuration,
    Duration periodicPollInterval,
    Duration executorRejectionDelay,
    int maxDispatchTasks) {

  public HarnessWorkDispatcherConfig {
    threadLeaseDuration = requireWholeMillisPositive(threadLeaseDuration, "threadLeaseDuration");
    modelLeaseDuration = requireWholeMillisPositive(modelLeaseDuration, "modelLeaseDuration");
    toolLeaseDuration = requireWholeMillisPositive(toolLeaseDuration, "toolLeaseDuration");
    periodicPollInterval = requireWholeMillisPositive(periodicPollInterval, "periodicPollInterval");
    executorRejectionDelay =
        requireWholeMillisPositive(executorRejectionDelay, "executorRejectionDelay");
    if (maxDispatchTasks <= 0) {
      throw new IllegalArgumentException("maxDispatchTasks must be positive");
    }
  }

  /** 按 target type 查找对应 lease duration。 */
  public Duration leaseDuration(WorkTargetType type) {
    return switch (Objects.requireNonNull(type, "type")) {
      case THREAD -> threadLeaseDuration;
      case MODEL -> modelLeaseDuration;
      case TOOL -> toolLeaseDuration;
    };
  }

  private static Duration requireWholeMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
