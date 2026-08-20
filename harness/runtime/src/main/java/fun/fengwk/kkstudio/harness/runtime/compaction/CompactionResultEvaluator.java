package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.util.Objects;

/** 把一次成功 Provider terminal 纯评估为 durable CompactionPayload 或稳定的语义错误。 */
public final class CompactionResultEvaluator {

  private CompactionResultEvaluator() {}

  public static EntryPayload evaluate(
      EntryPath path, CompactionStart start, ProviderResponse response) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(response, "response");
    AssistantError error = semanticError(response);
    CompactionPayload payload = null;
    if (error == null) {
      try {
        payload = CompactionSummaryAssembler.resultPayload(path, start, response.text());
      } catch (IllegalArgumentException invalidSummary) {
        error = new AssistantError("COMPACTION_INVALID_SUMMARY", invalidSummary.getMessage());
      }
    }
    if (error == null
        && start.phase() != CompactionPhase.HISTORY
        && !CompactionNoGain.hasGain(path, payload.summaryText(), start.cutEntryId())) {
      error =
          new AssistantError(
              CompactionNoGain.NO_GAIN_ERROR_CODE,
              "Compaction did not reduce the projected context size");
    }
    return error == null ? payload : new AssistantErrorPayload(error, null);
  }

  private static AssistantError semanticError(ProviderResponse response) {
    if (response.stopReason() == GenerationStopReason.LENGTH) {
      return new AssistantError(
          "COMPACTION_OUTPUT_TRUNCATED", "Compaction summary was truncated by the model");
    }
    if (response.stopReason() == GenerationStopReason.FILTERED) {
      return new AssistantError(
          "COMPACTION_CONTENT_FILTERED", "Compaction summary was filtered by the provider");
    }
    if (response.stopReason() != GenerationStopReason.COMPLETE) {
      return new AssistantError(
          "COMPACTION_INVALID_RESPONSE", "Compaction model returned an invalid stop reason");
    }
    if (!response.toolCalls().isEmpty()) {
      return new AssistantError(
          "COMPACTION_INVALID_RESPONSE", "Compaction model must not return tool calls");
    }
    if (response.text() == null || response.text().isBlank()) {
      return new AssistantError(
          "COMPACTION_EMPTY_SUMMARY", "Compaction model returned an empty summary");
    }
    return null;
  }
}
