package fun.fengwk.kkstudio.platform.harness.dispatch;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;

import java.time.Duration;

/**
 * HarnessWorkDispatcher 的部署级配置。
 *
 * <p>{@code leaseDuration} 是所有已注册 Work 类型的 claim 有效期；{@code periodicPollInterval} 是 fixed-delay 周期
 * poll 的间隔；{@code executorRejectionDelay} 是 worker executor 拒绝 handoff task 后仍 owned claim 的归还重排延迟。
 * 所有 Duration 必须为正的整毫秒，与 HarnessStore 的毫秒精度时间边界一致；宿主派发准入的拒绝重排延迟属于 Processor 配置（{@code
 * ModelProcessorConfig}/{@code ToolProcessorConfig}），因为它与 invocation 仍为 READY、肯定未发出请求的准入结果一起决策。
 *
 * <p>{@code maxDispatchTasks} 明确只约束 dispatcher 本地 queued/running 的 processor handoff task 总数（drain
 * 在达到上限后停止 claim）；它绝不约束异步 Model / Tool execution 的并发总数 —— 那些由各 Processor 及其注入的 Gateway / executor
 * 自行控制。
 */
public record HarnessWorkDispatcherConfig(
    Duration leaseDuration,
    Duration periodicPollInterval,
    Duration executorRejectionDelay,
    int maxDispatchTasks) {

  public HarnessWorkDispatcherConfig {
    leaseDuration =
        HarnessStoreTime.requireWholeMillisecondDuration(
            leaseDuration, "kk-studio.harness.dispatcher.lease-duration");
    periodicPollInterval =
        HarnessStoreTime.requireWholeMillisecondDuration(
            periodicPollInterval, "kk-studio.harness.dispatcher.poll-interval");
    executorRejectionDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(
            executorRejectionDelay, "kk-studio.harness.dispatcher.rejection-delay");
    if (maxDispatchTasks <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.harness.dispatcher.max-dispatch-tasks must be positive");
    }
  }
}
