package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;

/** ModelResponseValidator 直接单元测试：工具可见性 / arguments JSON / stopReason 语义。 */
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

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(name, "description of " + name, "{}");
  }

  private static ProviderRequest request(ProviderToolDefinition... tools) {
    return new ProviderRequest(
        new ModelDescriptor(
            "provider",
            1L,
            "model",
            ProviderType.OPENAI,
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
                BigDecimal.ZERO),
            PromptCachePolicy.disabled()),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(tools),
        ProviderCacheControl.none());
  }

  private static ProviderResponse response(
      List<ProviderToolCall> calls, ProviderStopReason stopReason) {
    return new ProviderResponse(
        "",
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
