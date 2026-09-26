package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;

import java.util.Objects;

/**
 * Assistant 完整响应在 Session 中持久化的不可变 Provider 元数据快照。
 *
 * <p>{@code decodeDurationMillis} 是 Harness 观测的流式生成计时（首个非空输出 delta 到成功回调观察时间的毫秒数），不是 Provider
 * 报告的事实：可选且可空，缺失或 null 表示无可信流计时（例如非流式响应、重试前的失败尝试或旧历史记录）。
 */
public record AssistantMessageMetadata(
    GenerationStopReason stopReason, ModelUsage usage, ModelCost cost, Long decodeDurationMillis) {

  public AssistantMessageMetadata {
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
    usage = Objects.requireNonNull(usage, "usage");
    cost = Objects.requireNonNull(cost, "cost");
    if (decodeDurationMillis != null && decodeDurationMillis < 0) {
      throw new IllegalArgumentException("decodeDurationMillis must be null or non-negative");
    }
  }

  /** 无可信流计时的 Provider 元数据快照。 */
  public AssistantMessageMetadata(
      GenerationStopReason stopReason, ModelUsage usage, ModelCost cost) {
    this(stopReason, usage, cost, null);
  }
}
