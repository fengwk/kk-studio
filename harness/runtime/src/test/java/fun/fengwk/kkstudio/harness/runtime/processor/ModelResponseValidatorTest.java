package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ModelResponseValidator 直接单元测试：只校验 canonical response 不变量与压缩调用约束；binding 可见性 / tool schema 校验 已移入
 * {@link ModelResponsePlanner}，stop reason 与 tool calls 正交。
 */
class ModelResponseValidatorTest {

  @Test
  void acceptsValidToolCallsResponse() {
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE)));
  }

  /** COMPLETE 与 LENGTH 都可以有或没有 calls：不存在 TOOL_CALLS 等价约束。 */
  @Test
  void acceptsOrthogonalStopReasonAndToolCalls() {
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                plainRequest(), response(List.of(), GenerationStopReason.COMPLETE)));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.LENGTH)));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                plainRequest(), response(List.of(), GenerationStopReason.LENGTH)));
  }

  @Test
  void rejectsDuplicateToolCallIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(
                        new ProviderToolCall("call_1", "bash", "{}"),
                        new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE)));
  }

  @Test
  void rejectsNonObjectToolArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "[1, 2]")),
                    GenerationStopReason.COMPLETE)));
  }

  @Test
  void rejectsTrailingTokensAndDuplicateArgumentFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1} extra")),
                    GenerationStopReason.COMPLETE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1,\"a\":2}")),
                    GenerationStopReason.COMPLETE)));
  }

  /** FILTERED 是唯一与 tool calls 冲突的 canonical 形状：FILTERED 的 calls 必须为空。 */
  @Test
  void rejectsFilteredResponsesWithToolCalls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                plainRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.FILTERED)));
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                plainRequest(), response(List.of(), GenerationStopReason.FILTERED)));
  }

  // -----------------------------------------------------------------------------------------------
  // compaction：行为与重构前保持一致（只允许 COMPLETE / 零 calls / 非空摘要文本，SUCCEEDED 前剥离 reserved sections）
  // -----------------------------------------------------------------------------------------------

  @Test
  void acceptsValidCompactionResponse() {
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), GenerationStopReason.COMPLETE, "structured summary")));
  }

  @Test
  void normalizesReservedFileSectionsBeforeCompactionSuccess() {
    ProviderResponse normalized =
        ModelResponseValidator.validate(
            compactionRequest(),
            response(
                List.of(),
                GenerationStopReason.COMPLETE,
                "structured summary\n\n<read-files>\nstale.txt\n</read-files>"));

    assertEquals("structured summary", normalized.text());
  }

  @Test
  void rejectsCompactionToolCalls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    GenerationStopReason.COMPLETE,
                    "structured summary")));
  }

  @Test
  void rejectsCompactionNonCompletedStopReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), GenerationStopReason.LENGTH, "structured summary")));
  }

  @Test
  void rejectsBlankCompactionText() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(), response(List.of(), GenerationStopReason.COMPLETE, " ")));
  }

  @Test
  void rejectsCompactionTextContainingOnlyReservedFileSections() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(
                    List.of(),
                    GenerationStopReason.COMPLETE,
                    "<read-files>\na.txt\n</read-files>")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), GenerationStopReason.COMPLETE, "summary\n<modified-files>")));
  }

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(name, "description of " + name, "{}");
  }

  private static ModelRequestSpec plainRequest() {
    return request();
  }

  private static ModelRequestSpec request(ProviderToolDefinition... tools) {
    ProviderRequest providerRequest =
        new ProviderRequest(
            new ModelDescriptor(
                "provider",
                "model",
                Set.of(ModelInputModality.TEXT),
                true,
                true,
                new ModelPricing(
                    "USD",
                    "standard",
                    "standard",
                    BigDecimal.ONE,
                    "1",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)),
            new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
            List.of(),
            List.of(tools),
            ProviderCacheControl.none());
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        providerRequest.model(),
        providerRequest.variant(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        providerRequest.cacheControl(),
        null);
  }

  private static ModelRequestSpec compactionRequest() {
    ProviderRequest providerRequest =
        new ProviderRequest(
            new ModelDescriptor(
                "provider",
                "model",
                Set.of(ModelInputModality.TEXT),
                false,
                false,
                new ModelPricing(
                    "USD",
                    "standard",
                    "standard",
                    BigDecimal.ONE,
                    "1",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)),
            new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
            List.of(),
            List.of(),
            ProviderCacheControl.none());
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        providerRequest.model(),
        providerRequest.variant(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        providerRequest.cacheControl(),
        new CompactionRequest(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            10_000L,
            new UUID(0L, 2L),
            new UUID(0L, 5L),
            null));
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
