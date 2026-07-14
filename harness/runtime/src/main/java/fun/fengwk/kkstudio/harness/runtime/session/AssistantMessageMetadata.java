package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import java.util.Objects;

/** Assistant 完整响应在 Session 中持久化的不可变 Provider 元数据快照。 */
public record AssistantMessageMetadata(
    ProviderStopReason stopReason, ModelUsage usage, ModelCost cost) {

  public AssistantMessageMetadata {
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
    usage = Objects.requireNonNull(usage, "usage");
    cost = Objects.requireNonNull(cost, "cost");
  }
}
