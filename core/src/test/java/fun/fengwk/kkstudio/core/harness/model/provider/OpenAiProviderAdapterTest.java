package fun.fengwk.kkstudio.core.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class OpenAiProviderAdapterTest {

  /** MiniMax reasoning 参数与 OpenAI cache key 必须在同一个 custom parameters map 中保留。 */
  @Test
  void mergesMinimaxReasoningSplitWithPromptCacheKey() {
    Map<String, Object> parameters =
        OpenAiProviderAdapter.customParameters(
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc-key"), true);

    assertEquals(Map.of("prompt_cache_key", "pc-key", "reasoning_split", true), parameters);
  }

  /** 有 reasoningEffort 时写入 reasoning_effort；空/null 不写入。 */
  @Test
  void includesReasoningEffortOnlyWhenProvided() {
    Map<String, Object> withEffort =
        OpenAiProviderAdapter.customParameters(ProviderCacheControl.none(), false, "high");
    assertEquals(Map.of("reasoning_effort", "high"), withEffort);

    Map<String, Object> withoutEffort =
        OpenAiProviderAdapter.customParameters(ProviderCacheControl.none(), false, null);
    assertEquals(Map.of(), withoutEffort);
  }

  /** Adapter 与连接描述的 Provider 类型不一致时，在创建模型前拒绝。 */
  @Test
  void rejectsMismatchedProviderDescriptor() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "provider",
            ProviderType.GOOGLE,
            "https://example.invalid",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)));

    assertThrows(
        IllegalArgumentException.class, () -> new OpenAiProviderAdapter("key").create(descriptor));
  }

  /**
   * Non-MiniMax OpenAI-compatible hosts still emit {@code <think>} in text when reasoning is
   * enabled; extraction must follow model.reasoning, not the host name.
   */
  @Test
  void extractsThinkTagsForReasoningModelsEvenOnNonMinimaxHosts() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "proxy",
            ProviderType.OPENAI,
            "https://gpt-load.example/proxy/openai_github/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)));
    ModelProvider provider = new OpenAiProviderAdapter("key").create(descriptor);
    LangChainModelProvider langchain = (LangChainModelProvider) provider;

    assertTrue(langchain.extractsThinkTags(request(true)));
    assertFalse(langchain.extractsThinkTags(request(false)));
  }

  private static ProviderRequest request(boolean reasoning) {
    ModelVariant variant =
        new ModelVariant("default", 256, 0.0, null, null, null, null, List.of(), null);
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "proxy-model",
            "Proxy",
            4096,
            256,
            Set.of(ModelInputModality.TEXT),
            true,
            reasoning,
            List.of(variant),
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());
  }
}
