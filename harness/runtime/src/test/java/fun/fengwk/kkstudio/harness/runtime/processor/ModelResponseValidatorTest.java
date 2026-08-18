package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ModelResponseValidator 直接单元测试：工具可见性 / arguments JSON / stopReason 语义与压缩调用约束。 */
class ModelResponseValidatorTest {

  @Test
  void acceptsValidToolCallsResponse() {
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsUndeclaredToolCall() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "undeclared", "{}")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsDuplicateDeclaredTools() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash"), tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsToolCallsWithNonToolCallStopReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{}")),
                    ProviderStopReason.COMPLETED)));
  }

  @Test
  void rejectsToolCallStopReasonWithoutCalls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")), response(List.of(), ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsDuplicateToolCallIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(
                        new ProviderToolCall("call_1", "bash", "{}"),
                        new ProviderToolCall("call_1", "bash", "{}")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsNonObjectToolArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "[1, 2]")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void rejectsTrailingTokensAndDuplicateArgumentFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1} extra")),
                    ProviderStopReason.TOOL_CALLS)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                request(tool("bash")),
                response(
                    List.of(new ProviderToolCall("call_1", "bash", "{\"a\":1,\"a\":2}")),
                    ProviderStopReason.TOOL_CALLS)));
  }

  @Test
  void acceptsValidCompactionResponse() {
    assertDoesNotThrow(
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), ProviderStopReason.COMPLETED, "structured summary")));
  }

  @Test
  void normalizesReservedFileSectionsBeforeCompactionSuccess() {
    ProviderResponse normalized =
        ModelResponseValidator.validate(
            compactionRequest(),
            response(
                List.of(),
                ProviderStopReason.COMPLETED,
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
                    ProviderStopReason.COMPLETED,
                    "structured summary")));
  }

  @Test
  void rejectsCompactionNonCompletedStopReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), ProviderStopReason.TOOL_CALLS, "structured summary")));
  }

  @Test
  void rejectsBlankCompactionText() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(), response(List.of(), ProviderStopReason.COMPLETED, " ")));
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
                    ProviderStopReason.COMPLETED,
                    "<read-files>\na.txt\n</read-files>")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelResponseValidator.validate(
                compactionRequest(),
                response(List.of(), ProviderStopReason.COMPLETED, "summary\n<modified-files>")));
  }

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(name, "description of " + name, "{}");
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
    List<ToolBinding> bindings = new ArrayList<>();
    for (ProviderToolDefinition tool : tools) {
      bindings.add(
          new ToolBinding(
              new ToolDescriptor(
                  tool.name(),
                  "1.0",
                  ToolType.PLATFORM,
                  tool.description(),
                  tool.name(),
                  new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
                  ToolSideEffect.READ_ONLY,
                  Duration.ofSeconds(30)),
              ToolType.PLATFORM,
              null));
    }
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        providerRequest.model(),
        providerRequest.variant(),
        List.of(),
        bindings,
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
      List<ProviderToolCall> calls, ProviderStopReason stopReason) {
    return response(calls, stopReason, "");
  }

  private static ProviderResponse response(
      List<ProviderToolCall> calls, ProviderStopReason stopReason, String text) {
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
