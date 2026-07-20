package fun.fengwk.kkstudio.harness.model.provider.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.Map;

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
}
