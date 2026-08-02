package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

import java.math.BigDecimal;
import java.util.List;

class ToolCallVisibilityTest {

  @Test
  void reportsTheFirstToolOutsideTheFrozenProviderRequest() {
    ProviderRequest request =
        request(
            List.of(
                new ProviderToolDefinition("read", "read", "{}"),
                new ProviderToolDefinition("bash", "bash", "{}")));
    ProviderToolCall read = new ProviderToolCall("call-read", "read", "{}");
    ProviderToolCall hidden = new ProviderToolCall("call-hidden", "hidden", "{}");

    assertEquals(List.of("read", "bash"), ToolCallVisibility.availableToolNames(request));
    assertEquals(
        hidden, ToolCallVisibility.firstUnavailable(request, response(read, hidden)).orElseThrow());
    assertTrue(ToolCallVisibility.firstUnavailable(request, response(read)).isEmpty());
    assertEquals(
        "tool is not available in this model invocation: hidden; available tools: [read, bash]",
        ToolCallVisibility.unavailableMessage("hidden", List.of("read", "bash")));
  }

  private static ProviderRequest request(List<ProviderToolDefinition> tools) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "default",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            0L,
            "model",
            ProviderType.OPENAI,
            false,
            false,
            pricing,
            PromptCachePolicy.disabled());
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(model, variant, List.of(), tools, ProviderCacheControl.none());
  }

  private static ProviderResponse response(ProviderToolCall... calls) {
    return new ProviderResponse(
        "",
        "",
        List.of(calls),
        ProviderStopReason.TOOL_CALLS,
        new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L),
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
        "{}");
  }
}
