package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.math.BigDecimal;
import java.util.List;

/** ModelStreamAccumulator 直接单元测试：text/thinking 累积、prefix/gap reconcile 与 tool-call fragment 校验。 */
class ModelStreamAccumulatorTest {

  @Test
  void accumulatesTextAndThinkingAndProjectsTrailingGaps() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("hel"));
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("thin"));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(response("hello", "thinking", List.of()));

    assertEquals("hello", completion.response().text());
    assertEquals("thinking", completion.response().thinking());
    assertEquals(
        List.of(
            new ProviderStreamEvent.TextDelta("lo"), new ProviderStreamEvent.ThinkingDelta("king")),
        completion.gaps());
    assertEquals("hello", accumulator.text());
    assertEquals("thinking", accumulator.thinking());
  }

  /** final response 省略 thinking 时，durable response 补入已 streamed 的 thinking。 */
  @Test
  void persistsStreamedThinkingWhenFinalOmitsIt() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("thin"));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(response("", "", List.of()));

    assertEquals("thin", completion.response().thinking());
    assertEquals("", completion.response().text());
  }

  /** streamed tool-call fragment 的 id/name/arguments 差异聚合成单个 gap delta。 */
  @Test
  void completesToolCallFragmentsWithGaps() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call", null, null));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, null, "bas", null));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"a\""));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(
            response("", "", List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1}"))));

    assertEquals(
        List.of(new ProviderStreamEvent.ToolCallDelta(0, "_1", "h", ":1}")), completion.gaps());
  }

  /** final 遗漏 streamed 过的 tool call：拒绝。 */
  @Test
  void rejectsFinalResponseOmittingStreamedToolCall() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{}"));

    assertThrows(
        IllegalArgumentException.class, () -> accumulator.complete(response("", "", List.of())));
  }

  /** final text 与 streamed text 冲突：拒绝。 */
  @Test
  void rejectsConflictingStreamedText() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("hello"));

    assertThrows(
        IllegalArgumentException.class,
        () -> accumulator.complete(response("world", "", List.of())));
  }

  /** tool-call identity 片段互相冲突：拒绝。 */
  @Test
  void rejectsConflictingToolCallIdentity() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));

    assertThrows(
        IllegalArgumentException.class,
        () -> accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null)));
  }

  /** final tool call 与 streamed 数据冲突：拒绝。 */
  @Test
  void rejectsFinalToolCallConflictingWithStreamedData() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            accumulator.complete(
                response("", "", List.of(new ProviderToolCall("other", "bash", "{}")))));
  }

  /** reconcile 校验失败不得把尚未发布的 final text/thinking gap 混入用户已见 partial。 */
  @Test
  void failedReconcileDoesNotMutateVisibleTextOrThinking() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("hel"));
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("thin"));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{}"));

    assertThrows(
        IllegalArgumentException.class,
        () -> accumulator.complete(response("hello", "thinking", List.of())));
    assertEquals("hel", accumulator.text());
    assertEquals("thin", accumulator.thinking());
  }

  /** 后序 tool-call 冲突不得提交前序 tool-call 的 preview gap。 */
  @Test
  void failedReconcileDoesNotPartiallyMutateToolCalls() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call", "bas", "{\"a\""));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(1, "call_2", "bash", "{}"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            accumulator.complete(
                response(
                    "",
                    "",
                    List.of(
                        new ProviderToolCall("call_1", "bash", "{\"a\":1}"),
                        new ProviderToolCall("other", "bash", "{}")))));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(
            response(
                "",
                "",
                List.of(
                    new ProviderToolCall("call_1", "bash", "{\"a\":1}"),
                    new ProviderToolCall("call_2", "bash", "{}"))));
    assertEquals(
        List.of(new ProviderStreamEvent.ToolCallDelta(0, "_1", "h", ":1}")), completion.gaps());
  }

  /** 未 stream 的 final tool call 也必须等全部 preview 成功后才进入 accumulator。 */
  @Test
  void failedReconcileDoesNotCreateUnpublishedToolCalls() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(1, "call_2", "bash", "{}"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            accumulator.complete(
                response(
                    "",
                    "",
                    List.of(
                        new ProviderToolCall("ghost", "read", "{}"),
                        new ProviderToolCall("other", "bash", "{}")))));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(
            response(
                "",
                "",
                List.of(
                    new ProviderToolCall("real", "write", "{\"ok\":true}"),
                    new ProviderToolCall("call_2", "bash", "{}"))));
    assertEquals(
        List.of(new ProviderStreamEvent.ToolCallDelta(0, "real", "write", "{\"ok\":true}")),
        completion.gaps());
  }

  private static ProviderResponse response(
      String text, String thinking, List<ProviderToolCall> calls) {
    return new ProviderResponse(
        text,
        thinking,
        calls,
        calls.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        null);
  }

  /** final thinking 与 streamed thinking 冲突：拒绝。 */
  @Test
  void rejectsConflictingStreamedThinking() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("think"));

    assertThrows(
        IllegalArgumentException.class,
        () -> accumulator.complete(response("", "thonk", List.of())));
  }

  /** final thinking 与 streamed thinking 等长：无 gap。 */
  @Test
  void equalLengthThinkingProducesNoGap() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("think"));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(response("", "think", List.of()));

    assertEquals(List.of(), completion.gaps());
  }

  /** tool-call identity 片段以完整值前缀重复出现：接受且不产生 identity gap。 */
  @Test
  void prefixToolCallFragmentIsAccepted() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call", null, null));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(
            response("", "", List.of(new ProviderToolCall("call_1", "bash", "{}"))));

    assertEquals(
        List.of(new ProviderStreamEvent.ToolCallDelta(0, null, "bash", "{}")), completion.gaps());
  }

  /** tool-call identity 片段逐步延长：接受并收敛为完整值。 */
  @Test
  void extendingToolCallFragmentIsAccepted() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call", null, null));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(
            response("", "", List.of(new ProviderToolCall("call_1", "bash", "{}"))));

    assertEquals(
        List.of(new ProviderStreamEvent.ToolCallDelta(0, null, "bash", "{}")), completion.gaps());
  }

  /** prepareComplete 产生 gap 但后续外部校验失败时，不污染 accumulator 既有的 text/thinking partial。 */
  @Test
  void prepareCompleteDoesNotMutateAccumulatorWhenSubsequentValidationFails() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("hello"));
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("think"));

    ProviderResponse candidateResponse = response("hello world", "think more", List.of());
    ModelStreamAccumulator.PreparedCompletion prepared =
        accumulator.prepareComplete(candidateResponse);

    assertEquals("hello", accumulator.text());
    assertEquals("think", accumulator.thinking());
    assertEquals(2, prepared.gaps().size());

    // 模拟外部校验失败（如 ModelResponseValidator 抛错），apply 绝未被调用
    RuntimeException validationFailure = new IllegalArgumentException("invalid response schema");
    assertNotNull(validationFailure);

    // accumulator 状态完全未被污染
    assertEquals("hello", accumulator.text());
    assertEquals("think", accumulator.thinking());

    // 仅在显式 apply 后状态才被推进
    ModelStreamAccumulator.Completion completion = prepared.apply();
    assertEquals("hello world", accumulator.text());
    assertEquals("think more", accumulator.thinking());
    assertEquals("hello world", completion.response().text());
    assertThrows(IllegalStateException.class, prepared::apply);
  }

  /** prepareComplete 遭遇前缀冲突抛异常时，也不污染 accumulator 状态。 */
  @Test
  void prepareCompletePrefixConflictLeavesAccumulatorUntouched() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("hello"));

    assertThrows(
        IllegalArgumentException.class,
        () -> accumulator.prepareComplete(response("conflict", "", List.of())));

    assertEquals("hello", accumulator.text());
  }

  /** 意图：验证空内容 delta 不会修改 accumulator 的 text/thinking，且终态 complete 与 gap 生成保持一致。 */
  @Test
  void emptyDeltasDoNotMutateAccumulatorContent() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta(""));
    accumulator.append(new ProviderStreamEvent.ThinkingDelta(""));
    assertEquals("", accumulator.text());
    assertEquals("", accumulator.thinking());

    accumulator.append(new ProviderStreamEvent.TextDelta("hello"));
    accumulator.append(new ProviderStreamEvent.TextDelta(""));
    accumulator.append(new ProviderStreamEvent.ThinkingDelta(""));
    assertEquals("hello", accumulator.text());
    assertEquals("", accumulator.thinking());

    ModelStreamAccumulator.Completion completion =
        accumulator.complete(response("hello", "", List.of()));
    assertEquals("hello", completion.response().text());
    assertEquals(List.of(), completion.gaps());
  }

  /** 意图：验证尾部不完整 tool-call 流出后，通过 diagnostic 成功 reconcile 且不再误报遗漏。 */
  @Test
  void reconcilesTrailingIncompleteToolCallDiagnostic() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_0", "weather", "{\"loc\":"));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, null, null, "\"Paris\"}"));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(1, "call_1", "calc", "{\"expr\":"));

    ProviderToolCall completeCall =
        new ProviderToolCall("call_0", "weather", "{\"loc\":\"Paris\"}");
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            1,
            "call_1",
            "calc",
            "{\"expr\":",
            "model output truncated before tool arguments formed valid JSON");

    ProviderResponse resp =
        new ProviderResponse(
            "",
            "",
            List.of(completeCall),
            GenerationStopReason.LENGTH,
            new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
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
            "{}",
            List.of(diagnostic));

    ModelStreamAccumulator.Completion completion = accumulator.complete(resp);
    assertEquals(List.of(), completion.gaps());
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals(1, completion.response().toolCallDiagnostics().size());
  }

  /** 意图：验证 complete 与 incomplete 混合时按原始 index 准确校验与生成 gap。 */
  @Test
  void reconcilesMixedCompleteAndIncompleteToolCalls() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    // call 0 是 incomplete，call 1 是 complete
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_0", "search", "{\"q\":"));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(1, "call_1", "calc", null));

    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            0,
            "call_0",
            "search",
            "{\"q\":",
            "model output truncated before tool arguments formed valid JSON");
    ProviderToolCall completeCall = new ProviderToolCall("call_1", "calc", "{\"expr\":\"1+1\"}");

    ProviderResponse resp =
        new ProviderResponse(
            "",
            "",
            List.of(completeCall),
            GenerationStopReason.LENGTH,
            new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
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
            "{}",
            List.of(diagnostic));

    ModelStreamAccumulator.Completion completion = accumulator.complete(resp);
    assertEquals(1, completion.gaps().size());
    assertEquals(
        new ProviderStreamEvent.ToolCallDelta(1, null, null, "{\"expr\":\"1+1\"}"),
        completion.gaps().get(0));
  }

  /** 意图：验证 diagnostic 的 observed 字段与流式片段冲突时拒绝。 */
  @Test
  void rejectsConflictingDiagnostic() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(
        new ProviderStreamEvent.ToolCallDelta(0, "call_0", "search", "{\"q\":\"abc\""));

    ProviderToolCallDiagnostic conflictArgs =
        new ProviderToolCallDiagnostic(0, "call_0", "search", "{\"q\":\"other\"", "truncated");

    ProviderResponse resp =
        new ProviderResponse(
            "",
            "",
            List.of(),
            GenerationStopReason.LENGTH,
            new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
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
            "{}",
            List.of(conflictArgs));

    assertThrows(IllegalArgumentException.class, () -> accumulator.complete(resp));
  }

  /** 意图：验证 thinking 缺失补齐时，durable response 依然完整保留 toolCallDiagnostics。 */
  @Test
  void persistsDiagnosticsWhenThinkingIsSynthesized() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ThinkingDelta("deep thought"));
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "call_0", "search", "{\"q\":"));

    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(0, "call_0", "search", "{\"q\":", "truncated");

    ProviderResponse respWithoutThinking =
        new ProviderResponse(
            "",
            "",
            List.of(),
            GenerationStopReason.LENGTH,
            new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
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
            "{}",
            List.of(diagnostic));

    ModelStreamAccumulator.Completion completion = accumulator.complete(respWithoutThinking);
    assertEquals("deep thought", completion.response().thinking());
    assertEquals(1, completion.response().toolCallDiagnostics().size());
    assertEquals(diagnostic, completion.response().toolCallDiagnostics().get(0));
  }

  /** 意图：验证 FILTERED 终态触发 provider protocol withdrawal，安全忽略已流出的 tool fragment，不产生 gap 也不报错。 */
  @Test
  void filteredStopReasonIgnoresStreamedToolFragmentsWithoutGapOrError() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.TextDelta("I cannot proceed."));
    accumulator.append(
        new ProviderStreamEvent.ToolCallDelta(0, "call_withdrawn", "run_cmd", "{\"cmd\":"));

    ProviderResponse filteredResp =
        new ProviderResponse(
            "I cannot proceed.",
            "",
            List.of(),
            GenerationStopReason.FILTERED,
            new ModelUsage(10L, 5L, 0L, 0L, 0L, 0L, 15L),
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
            "{}",
            List.of());

    ModelStreamAccumulator.Completion completion = accumulator.complete(filteredResp);
    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertEquals("I cannot proceed.", completion.response().text());
    assertTrue(completion.response().toolCalls().isEmpty());
    assertTrue(completion.response().toolCallDiagnostics().isEmpty());
    assertTrue(completion.gaps().isEmpty());
  }

  /** 意图：验证 appendIdentity 在 tool call identity 冲突时，异常消息脱敏，不泄漏原始值且无 cause。 */
  @Test
  void appendIdentityThrowsSanitizedExceptionWithoutCauseOrLeakedSecrets() {
    ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
    accumulator.append(new ProviderStreamEvent.ToolCallDelta(0, "prefix_SECRET_1", null, null));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                accumulator.append(
                    new ProviderStreamEvent.ToolCallDelta(0, "conflict_SECRET_2", null, null)));

    assertEquals("streamed tool call identity conflict", ex.getMessage());
    assertFalse(ex.getMessage().contains("SECRET"));
    assertNull(ex.getCause());
  }
}
