package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

/** Evaluates a completed provider response as a durable compaction payload or semantic error. */
public final class CompactionResultEvaluator {

  private CompactionResultEvaluator() {}

  public static EntryPayload evaluate(
      String summaryText, AssistantMessageMetadata metadata, long outputBudget) {
    AssistantError error = semanticError(summaryText, metadata);
    if (error == null) {
      long estimatedTokens = TokenEstimator.estimateTextTokens(summaryText);
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
    return error == null
        ? new CompactionPayload(summaryText, metadata)
        : new AssistantErrorPayload(error, null);
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
