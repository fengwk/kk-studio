package fun.fengwk.kkstudio.harness.model.provider;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import java.util.List;
import java.util.Objects;

/** Provider 流完成时提供的完整响应快照。 */
public record ProviderResponse(
    String text,
    String thinking,
    List<ProviderToolCall> toolCalls,
    ProviderStopReason stopReason,
    ModelUsage usage,
    ModelCost cost) {

  public ProviderResponse {
    text = text == null ? "" : text;
    thinking = thinking == null ? "" : thinking;
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
  }
}
