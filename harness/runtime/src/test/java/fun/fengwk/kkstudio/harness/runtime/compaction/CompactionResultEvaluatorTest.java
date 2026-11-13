package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

import java.util.Map;

/** Maps provider terminal semantics to a direct summary payload or stable semantic error. */
class CompactionResultEvaluatorTest {

  private static final long BUDGET = 4096L;

  @Test
  void mapsSemanticTerminalFailuresWithoutProviderRetry() {
    assertError(
        "COMPACTION_OUTPUT_TRUNCATED",
        CompactionResultEvaluator.evaluate(
            "partial", metadata(GenerationStopReason.LENGTH), BUDGET));
    assertError(
        "COMPACTION_CONTENT_FILTERED",
        CompactionResultEvaluator.evaluate("", metadata(GenerationStopReason.FILTERED), BUDGET));
    assertError(
        "COMPACTION_INVALID_RESPONSE", CompactionResultEvaluator.evaluate("summary", null, BUDGET));
    assertError(
        "COMPACTION_EMPTY_SUMMARY",
        CompactionResultEvaluator.evaluate(" ", metadata(GenerationStopReason.COMPLETE), BUDGET));
    assertError(
        "COMPACTION_BUDGET_EXCEEDED",
        CompactionResultEvaluator.evaluate(
            "very long summary that definitely exceeds a budget of one token",
            metadata(GenerationStopReason.COMPLETE),
            1L));
  }

  @Test
  void successfulSummaryIsStoredVerbatimWithProviderMetadata() {
    AssistantMessageMetadata metadata = metadata(GenerationStopReason.COMPLETE);

    EntryPayload result = CompactionResultEvaluator.evaluate("summary", metadata, BUDGET);

    CompactionPayload payload = assertInstanceOf(CompactionPayload.class, result);
    assertEquals("summary", payload.summaryText());
    assertEquals(metadata, payload.assistantMetadata());
  }

  private static AssistantMessageMetadata metadata(GenerationStopReason stopReason) {
    return new AssistantMessageMetadata(
        stopReason, new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 2L), 100L);
  }

  private static void assertError(String code, EntryPayload result) {
    AssistantErrorPayload error = assertInstanceOf(AssistantErrorPayload.class, result);
    assertEquals(code, error.error().code());
  }
}
