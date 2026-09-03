package fun.fengwk.kkstudio.platform.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** {@link OpenAiResponsesProviderAdapter} 的单元测试。 */
class OpenAiResponsesProviderAdapterTest {

  /** 构造时 apiKey 为 null 必须立即抛 NPE。 */
  @Test
  void rejectsNullApiKey() {
    assertThrows(NullPointerException.class, () -> new OpenAiResponsesProviderAdapter(null));
  }

  /** providerType 固定为 OPENAI_RESPONSES。 */
  @Test
  void returnsOpenAiResponsesProviderType() {
    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter("key");
    assertEquals(ProviderType.OPENAI_RESPONSES, adapter.providerType());
  }

  /** ProviderDescriptor 类型不匹配时拒绝创建。 */
  @Test
  void rejectsMismatchedProviderDescriptor() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "provider",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)));

    assertThrows(
        IllegalArgumentException.class,
        () -> new OpenAiResponsesProviderAdapter("key").create(descriptor));
  }

  /** validateRequest 拒绝非法缓存控制（如 LONG）。 */
  @Test
  void validateRequestRejectsInvalidCacheControl() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "provider",
            ProviderType.OPENAI_RESPONSES,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)));
    ModelProvider provider = new OpenAiResponsesProviderAdapter("key").create(descriptor);
    LangChainModelProvider langchain = (LangChainModelProvider) provider;

    assertThrows(
        IllegalArgumentException.class,
        () ->
            langchain.validateRequest(
                request(
                    ProviderCacheControl.affinity(PromptCacheRetention.LONG, "aff-key"), null)));
  }

  /** chatModel 正确构建出 OpenAiResponsesStreamingChatModel 实例。 */
  @Test
  void chatModelBuildsStreamingChatModel() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "provider",
            ProviderType.OPENAI_RESPONSES,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(3), Duration.ofSeconds(3)));
    ModelProvider provider = new OpenAiResponsesProviderAdapter("key").create(descriptor);
    LangChainModelProvider langchain = (LangChainModelProvider) provider;

    StreamingChatModel model =
        langchain.chatModel(
            request(ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "aff-key"), "high"));
    assertNotNull(model);
    assertEquals(OpenAiResponsesStreamingChatModel.class, model.getClass());
  }

  private static ProviderRequest request(ProviderCacheControl control, String reasoningEffort) {
    ModelVariant variant =
        new ModelVariant("default", 256, 0.0, null, null, null, null, List.of(), reasoningEffort);
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            "gpt-4o",
            Set.of(ModelInputModality.TEXT),
            true,
            reasoningEffort != null,
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
                BigDecimal.ZERO));
    return new ProviderRequest(model, variant, List.of(), List.of(), control);
  }
}
