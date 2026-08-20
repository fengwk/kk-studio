package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

import java.math.BigDecimal;
import java.util.List;

/** ModelResponseValidator 直接单元测试：只校验所有调用共享的 canonical response 不变量。 */
class ModelResponseValidatorTest {

  @Test
  void acceptsValidToolCallsResponse() {
    // 验证合法 tool intent 不依赖 generation stop reason 的特殊枚举值。
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE)));
  }

  @Test
  void acceptsOrthogonalStopReasonAndToolCalls() {
    // COMPLETE/LENGTH 与 tool intent 正交；validator 不把 tool presence 改写为 stop reason。
    assertDoesNotThrow(
        () -> ModelResponseValidator.validate(response(List.of(), GenerationStopReason.COMPLETE)));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.LENGTH)));
    assertDoesNotThrow(
        () -> ModelResponseValidator.validate(response(List.of(), GenerationStopReason.LENGTH)));
  }

  @Test
  void rejectsMalformedToolCalls() {
    // ID 唯一、名称非空、arguments 为严格 JSON object 是所有 Provider response 的共同边界。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(
                        new ProviderToolCall("call_1", "bash", "{}"),
                        new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("", "bash", "{}")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "", "{}")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "[1, 2]")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1} extra")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1,\"a\":2}")),
                    GenerationStopReason.COMPLETE)));
  }

  @Test
  void filteredResponsesMustNotContainToolCalls() {
    // FILTERED 是唯一与 tool intent 冲突的 canonical generation 形状。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.FILTERED)));
    assertDoesNotThrow(
        () -> ModelResponseValidator.validate(response(List.of(), GenerationStopReason.FILTERED)));
  }

  @Test
  void leavesCompactionSemanticShapesToReducer() {
    // LENGTH、tool calls、空摘要与 reserved sections 都由 Compaction reducer 变成稳定业务结果，而非 transport retry。
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE,
                    "structured summary")));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(List.of(), GenerationStopReason.LENGTH, "structured summary")));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(List.of(), GenerationStopReason.COMPLETE, " ")));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                response(
                    List.of(),
                    GenerationStopReason.COMPLETE,
                    "<read-files>\na.txt\n</read-files>")));
  }

  private static ProviderResponse response(
      List<ProviderToolCall> calls, GenerationStopReason stopReason) {
    return response(calls, stopReason, "");
  }

  private static ProviderResponse response(
      List<ProviderToolCall> calls, GenerationStopReason stopReason, String text) {
    return new ProviderResponse(
        text,
        null,
        calls,
        stopReason,
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
}
