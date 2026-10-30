package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.RequestBodyPricingSnapshot;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.time.Instant;
import java.util.Objects;

/**
 * Assistant 完整响应在 Session 中持久化的不可变 Provider 元数据快照。
 *
 * <p>只承载 Provider 报告的 stopReason 与 usage。成本不是持久事实：读取投影阶段按当前 catalog pricing 用 {@code
 * ModelCost.calculate} 现算。
 *
 * <p>{@code decodeDurationMillis} 是 Harness 观测的流式生成计时（首个非空输出 delta 到成功回调观察时间的毫秒数），不是 Provider
 * 报告的事实：可选且可空，缺失或 null 表示无可信流计时（例如非流式响应、重试前的失败尝试或旧历史记录）。
 *
 * <p>{@code requestBodyPricing} 是成功 attempt 的条件指纹匹配快照，只含哈希与布尔值；null 表示没有可信观察或旧历史记录。
 */
public record AssistantMessageMetadata(
    GenerationStopReason stopReason,
    ModelUsage usage,
    Long decodeDurationMillis,
    ModelSelection model,
    Instant requestedAt,
    String responseServiceTier,
    RequestBodyPricingSnapshot requestBodyPricing) {

  public AssistantMessageMetadata {
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
    usage = Objects.requireNonNull(usage, "usage");
    if (decodeDurationMillis != null && decodeDurationMillis < 0) {
      throw new IllegalArgumentException("decodeDurationMillis must be null or non-negative");
    }
    if (responseServiceTier != null && responseServiceTier.isBlank()) {
      throw new IllegalArgumentException("responseServiceTier must be null or non-blank");
    }
  }

  public AssistantMessageMetadata(
      GenerationStopReason stopReason, ModelUsage usage, Long decodeDurationMillis) {
    this(stopReason, usage, decodeDurationMillis, null, null, null, null);
  }

  public AssistantMessageMetadata(
      GenerationStopReason stopReason,
      ModelUsage usage,
      Long decodeDurationMillis,
      ModelSelection model,
      Instant requestedAt,
      String responseServiceTier) {
    this(stopReason, usage, decodeDurationMillis, model, requestedAt, responseServiceTier, null);
  }

  /** 无可信流计时的 Provider 元数据快照。 */
  public AssistantMessageMetadata(GenerationStopReason stopReason, ModelUsage usage) {
    this(stopReason, usage, null);
  }
}
