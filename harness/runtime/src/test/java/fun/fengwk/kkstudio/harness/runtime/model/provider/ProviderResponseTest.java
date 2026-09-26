package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** 验证 ProviderResponse 对 toolCallDiagnostics 的不变量校验（非空、唯一性、连续可解释性与 stopReason 约束）。 */
class ProviderResponseTest {

  private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  @Test
  void acceptsValidCompleteAndDiagnosticOutcomes() {
    ProviderToolCall call = new ProviderToolCall("c1", "tool_a", "{}");
    ProviderToolCallDiagnostic diag =
        new ProviderToolCallDiagnostic(1, "c2", "tool_b", "{", "truncated");

    ProviderResponse resp =
        new ProviderResponse(
            "text",
            "thinking",
            List.of(call),
            GenerationStopReason.LENGTH,
            USAGE,
            COST,
            "req-1",
            "standard",
            "{}",
            List.of(diag));

    assertEquals(1, resp.toolCalls().size());
    assertEquals(1, resp.toolCallDiagnostics().size());
    assertEquals(1, resp.toolCallDiagnostics().get(0).callIndex());
  }

  @Test
  void rejectsNullDiagnosticElement() {
    assertThrows(
        NullPointerException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(),
                GenerationStopReason.LENGTH,
                USAGE,
                COST,
                null,
                null,
                "{}",
                Collections.singletonList(null)));
  }

  @Test
  void rejectsDiagnosticsOnFilteredResponse() {
    ProviderToolCallDiagnostic diag =
        new ProviderToolCallDiagnostic(0, "c1", "tool", "{", "truncated");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(),
                GenerationStopReason.FILTERED,
                USAGE,
                COST,
                null,
                null,
                "{}",
                List.of(diag)));
  }

  /** CONTINUE 是纯协议续写终止态：既不能携带 calls，也不能携带被丢弃的 calls 的 diagnostics。 */
  @Test
  void rejectsToolIntentOnContinuationResponse() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(new ProviderToolCall("c1", "tool_a", "{}")),
                GenerationStopReason.CONTINUE,
                USAGE,
                COST,
                null,
                null,
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(),
                GenerationStopReason.CONTINUE,
                USAGE,
                COST,
                null,
                null,
                "{}",
                List.of(new ProviderToolCallDiagnostic(0, "c1", "tool_a", "{", "truncated"))));
  }

  /** withToolCalls 必须复用同一 stop-reason 约束，不能绕过 CONTINUE 的零工具意图要求。 */
  @Test
  void continuationCopyWithToolCallsIsRejected() {
    ProviderResponse continuation =
        new ProviderResponse(
            "paused", "", List.of(), GenerationStopReason.CONTINUE, USAGE, COST, null, null, "{}");

    assertEquals(GenerationStopReason.CONTINUE, continuation.stopReason());
    assertThrows(
        IllegalArgumentException.class,
        () -> continuation.withToolCalls(List.of(new ProviderToolCall("c1", "tool_a", "{}"))));
  }

  /**
   * decodeDurationMillis 是 Harness 观测的可空非负计时：Provider 构造与 withToolCalls 默认不引入计时，
   * withDecodeDurationMillis 只替换计时并保留其余事实，负值被拒绝。
   */
  @Test
  void validatesOptionalDecodeDuration() {
    ProviderResponse base =
        new ProviderResponse(
            "text",
            "thinking",
            List.of(),
            GenerationStopReason.COMPLETE,
            USAGE,
            COST,
            null,
            null,
            "{}");

    assertNull(base.decodeDurationMillis());
    assertEquals(0L, base.withDecodeDurationMillis(0L).decodeDurationMillis());

    ProviderResponse timed = base.withDecodeDurationMillis(1234L);
    assertEquals(1234L, timed.decodeDurationMillis());
    assertEquals(base.text(), timed.text());
    assertEquals(base.thinking(), timed.thinking());
    assertEquals(base.usage(), timed.usage());
    assertEquals(base.cost(), timed.cost());
    assertEquals(timed, timed.withDecodeDurationMillis(1234L));
    // withToolCalls 是替换副本：必须保留已冻结的计时。
    assertEquals(
        1234L,
        timed
            .withToolCalls(List.of(new ProviderToolCall("c1", "tool_a", "{}")))
            .decodeDurationMillis());

    assertThrows(IllegalArgumentException.class, () -> base.withDecodeDurationMillis(-1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "text",
                "thinking",
                List.of(),
                GenerationStopReason.COMPLETE,
                USAGE,
                COST,
                null,
                null,
                "{}",
                List.of(),
                -5L));
  }

  @Test
  void rejectsOutOfBoundIndex() {
    ProviderToolCall call = new ProviderToolCall("c1", "tool_a", "{}");
    // totalOutcomes = 1 call + 1 diag = 2. index 2 is out of bounds (allowed: 0, 1)
    ProviderToolCallDiagnostic diagOutOfBounds =
        new ProviderToolCallDiagnostic(2, "c2", "tool_b", "{", "truncated");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(call),
                GenerationStopReason.LENGTH,
                USAGE,
                COST,
                null,
                null,
                "{}",
                List.of(diagOutOfBounds)));
  }

  @Test
  void rejectsDuplicateDiagnosticIndex() {
    ProviderToolCall call = new ProviderToolCall("c1", "tool_a", "{}");
    // totalOutcomes = 1 call + 2 diags = 3. both diags have index 1
    ProviderToolCallDiagnostic diag1 =
        new ProviderToolCallDiagnostic(1, "c2", "tool_b", "{", "truncated");
    ProviderToolCallDiagnostic diag2 =
        new ProviderToolCallDiagnostic(1, "c3", "tool_c", "{", "truncated");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                "",
                "",
                List.of(call),
                GenerationStopReason.LENGTH,
                USAGE,
                COST,
                null,
                null,
                "{}",
                List.of(diag1, diag2)));
  }

  @Test
  void rawUsageJsonRejectsDuplicateKeysAndTrailingTokensWithoutExposingCause() {
    // 1. 重复 key 拒绝
    IllegalArgumentException exDuplicate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProviderResponse(
                    "",
                    "",
                    List.of(),
                    GenerationStopReason.COMPLETE,
                    USAGE,
                    COST,
                    null,
                    null,
                    "{\"input_tokens\":10,\"input_tokens\":20}",
                    List.of()));
    assertEquals("rawUsageJson must contain JSON", exDuplicate.getMessage());
    assertNull(exDuplicate.getCause());

    // 2. trailing token 拒绝
    IllegalArgumentException exTrailing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProviderResponse(
                    "",
                    "",
                    List.of(),
                    GenerationStopReason.COMPLETE,
                    USAGE,
                    COST,
                    null,
                    null,
                    "{\"input_tokens\":10} extra_garbage",
                    List.of()));
    assertEquals("rawUsageJson must contain JSON", exTrailing.getMessage());
    assertNull(exTrailing.getCause());
  }
}
