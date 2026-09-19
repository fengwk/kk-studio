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
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Compaction Provider terminal 到 durable result/error 的纯语义映射。 */
class CompactionResultEvaluatorTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  private static final Instant NOW = Instant.EPOCH;

  @Test
  void mapsSemanticTerminalFailuresWithoutProviderRetry() {
    EntryPath path = historyPath();
    CompactionStart start =
        (CompactionStart) ((TurnStartPayload) path.head().payload()).compaction();

    assertError(
        "COMPACTION_OUTPUT_TRUNCATED",
        CompactionResultEvaluator.evaluate(
            path, start, response("partial", GenerationStopReason.LENGTH, List.of())));
    assertError(
        "COMPACTION_CONTENT_FILTERED",
        CompactionResultEvaluator.evaluate(
            path, start, response("", GenerationStopReason.FILTERED, List.of())));
    assertError(
        "COMPACTION_INVALID_RESPONSE",
        CompactionResultEvaluator.evaluate(
            path,
            start,
            response(
                "summary",
                GenerationStopReason.COMPLETE,
                List.of(new ProviderToolCall("call-1", "bash", "{}")))));
    assertError(
        "COMPACTION_EMPTY_SUMMARY",
        CompactionResultEvaluator.evaluate(
            path, start, response(" ", GenerationStopReason.COMPLETE, List.of())));
    assertError(
        "COMPACTION_INVALID_SUMMARY",
        CompactionResultEvaluator.evaluate(
            path,
            start,
            response(
                "<read-files>\na.txt\n</read-files>", GenerationStopReason.COMPLETE, List.of())));
  }

  @Test
  void historySuccessProducesMinimalPayload() {
    EntryPath path = historyPath();
    CompactionStart start = ((TurnStartPayload) path.head().payload()).compaction();

    EntryPayload result =
        CompactionResultEvaluator.evaluate(
            path, start, response("summary", GenerationStopReason.COMPLETE, List.of()));

    assertEquals(new CompactionPayload("summary"), result);
  }

  private static EntryPath historyPath() {
    CompactionStart start =
        new CompactionStart(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(1L),
            id(1L),
            null);
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

  private static ProviderResponse response(
      String text, GenerationStopReason stopReason, List<ProviderToolCall> calls) {
    return new ProviderResponse(
        text,
        "",
        calls,
        stopReason,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        null,
        null,
        null);
  }

  private static void assertError(String code, EntryPayload result) {
    AssistantErrorPayload error = assertInstanceOf(AssistantErrorPayload.class, result);
    assertEquals(code, error.error().code());
  }
}
