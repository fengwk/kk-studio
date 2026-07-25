package fun.fengwk.kkstudio.core.harness.model.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.Objects;

/** Google Gemini Provider adapter。 */
public final class GoogleProviderAdapter implements ProviderAdapter {

  private final String apiKey;

  public GoogleProviderAdapter(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.GOOGLE;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    OpenAiProviderAdapter.requireType(descriptor, providerType());
    return new LangChainModelProvider(providerType()) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        // AUTOMATIC：忽略 cacheControl；harness 不发送任何 cached-content resource。
        return GoogleAiGeminiStreamingChatModel.builder()
            .baseUrl(descriptor.endpoint())
            .apiKey(apiKey)
            .timeout(descriptor.modelCallTimeoutPolicy().modelCallTimeout())
            .returnThinking(true)
            .build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        OpenAiProviderAdapter.requireProviderType(request, providerType());
        // AUTOMATIC 模式下 Provider 自行决定缓存命中，control 被忽略。
      }
    };
  }
}
