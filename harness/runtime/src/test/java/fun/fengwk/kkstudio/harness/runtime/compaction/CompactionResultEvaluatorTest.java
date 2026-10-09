package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;

import java.time.Instant;
import java.util.List;

/** Compaction terminal 到 durable result/error 的纯语义映射。 */
class CompactionResultEvaluatorTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  private static final Instant NOW = Instant.EPOCH;
  private static final long BUDGET = 4096L;

  @Test
  void mapsSemanticTerminalFailuresWithoutProviderRetry() {
    EntryPath path = historyPath();
    CompactionStart start = ((TurnStartPayload) path.head().payload()).compaction();

    assertError(
        "COMPACTION_OUTPUT_TRUNCATED",
        CompactionResultEvaluator.evaluate(
            path, start, "partial", metadata(GenerationStopReason.LENGTH), BUDGET));
    assertError(
        "COMPACTION_CONTENT_FILTERED",
        CompactionResultEvaluator.evaluate(
            path, start, "", metadata(GenerationStopReason.FILTERED), BUDGET));
    assertError(
        "COMPACTION_INVALID_RESPONSE",
        CompactionResultEvaluator.evaluate(path, start, "summary", null, BUDGET));
    assertError(
        "COMPACTION_EMPTY_SUMMARY",
        CompactionResultEvaluator.evaluate(
            path, start, " ", metadata(GenerationStopReason.COMPLETE), BUDGET));
    assertError(
        "COMPACTION_INVALID_SUMMARY",
        CompactionResultEvaluator.evaluate(
            path,
            start,
            "<read-files>\na.txt\n</read-files>",
            metadata(GenerationStopReason.COMPLETE),
            BUDGET));
    assertError(
        "COMPACTION_BUDGET_EXCEEDED",
        CompactionResultEvaluator.evaluate(
            path,
            start,
            "very long summary that definitely exceeds a budget of one token",
            metadata(GenerationStopReason.COMPLETE),
            1L));
  }

  @Test
  void historySuccessProducesMinimalPayload() {
    EntryPath path = historyPath();
    CompactionStart start = ((TurnStartPayload) path.head().payload()).compaction();
    AssistantMessageMetadata meta = metadata(GenerationStopReason.COMPLETE);

    EntryPayload result = CompactionResultEvaluator.evaluate(path, start, "summary", meta, BUDGET);

    CompactionPayload payload = assertInstanceOf(CompactionPayload.class, result);
    assertEquals("summary", payload.summaryText());
    assertEquals(meta, payload.assistantMetadata());
  }

  private static EntryPath historyPath() {
    CompactionStart start =
        new CompactionStart(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            BUDGET,
            id(1L),
            id(1L),
            null,
            id(300L),
            id(400L));
    return new EntryPath(
        List.of(
            new Entry(id(1L), id(100L), null, new RootPayload(SETTINGS), NOW),
            new Entry(
                id(2L),
                id(100L),
                id(1L),
                new TurnStartPayload(
                    TurnStartReason.COMPACTION, SETTINGS, id(200L), 100_000, 16_384, start),
                NOW)));
  }

  private static AssistantMessageMetadata metadata(GenerationStopReason stopReason) {
    return new AssistantMessageMetadata(stopReason, new ModelUsage(1, 1, 0, 0, 0, 0, 2), 100L);
  }

  private static void assertError(String code, EntryPayload result) {
    AssistantErrorPayload error = assertInstanceOf(AssistantErrorPayload.class, result);
    assertEquals(code, error.error().code());
  }
}
