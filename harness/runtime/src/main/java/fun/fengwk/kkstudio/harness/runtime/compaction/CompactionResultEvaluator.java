package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

import java.util.Objects;

/** 把一次成功 Provider terminal 纯评估为 durable CompactionPayload 或稳定的语义错误。 */
public final class CompactionResultEvaluator {

  private CompactionResultEvaluator() {}

  public static EntryPayload evaluate(
      EntryPath parentPath,
      CompactionStart start,
      String summaryText,
      AssistantMessageMetadata metadata,
      long outputBudget) {
    Objects.requireNonNull(parentPath, "parentPath");
    Objects.requireNonNull(start, "start");
    AssistantError error = semanticError(summaryText, metadata);
    CompactionPayload payload = null;
    if (error == null) {
      try {
        CompactionPayload summary =
            CompactionSummaryAssembler.resultPayload(parentPath, start, summaryText);
        payload = new CompactionPayload(summary.summaryText(), metadata);
      } catch (IllegalArgumentException invalidSummary) {
        error = new AssistantError("COMPACTION_INVALID_SUMMARY", invalidSummary.getMessage());
      }
    }
    if (error == null) {
      long estimatedTokens = TokenEstimator.estimateTextTokens(payload.summaryText());
      if (estimatedTokens > outputBudget) {
        error =
            new AssistantError(
                "COMPACTION_BUDGET_EXCEEDED",
                "Compaction summary token count "
                    + estimatedTokens
                    + " exceeded the configured budget "
                    + outputBudget);
      }
    }
    return error == null ? payload : new AssistantErrorPayload(error, null);
  }

  private static AssistantError semanticError(
      String summaryText, AssistantMessageMetadata metadata) {
    if (metadata == null) {
      return new AssistantError(
          "COMPACTION_INVALID_RESPONSE", "Compaction model returned an invalid stop reason");
    }
    if (metadata.stopReason() == GenerationStopReason.LENGTH) {
      return new AssistantError(
          "COMPACTION_OUTPUT_TRUNCATED", "Compaction summary was truncated by the model");
    }
    if (metadata.stopReason() == GenerationStopReason.FILTERED) {
      return new AssistantError(
          "COMPACTION_CONTENT_FILTERED", "Compaction summary was filtered by the provider");
    }
    if (metadata.stopReason() != GenerationStopReason.COMPLETE) {
      return new AssistantError(
          "COMPACTION_INVALID_RESPONSE", "Compaction model returned an invalid stop reason");
    }
    if (summaryText == null || summaryText.isBlank()) {
      return new AssistantError(
          "COMPACTION_EMPTY_SUMMARY", "Compaction model returned an empty summary");
    }
    return null;
  }
}
