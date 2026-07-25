package fun.fengwk.kkstudio.core.harness.model.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.Objects;

/** OpenAI Responses API Provider adapter。 */
public final class OpenAiResponsesProviderAdapter implements ProviderAdapter {

  private final String apiKey;

  public OpenAiResponsesProviderAdapter(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI_RESPONSES;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    OpenAiProviderAdapter.requireType(descriptor, providerType());
    return new LangChainModelProvider(providerType()) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        String key = CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
        OpenAiOfficialResponsesStreamingChatModel.Builder builder =
            OpenAiOfficialResponsesStreamingChatModel.builder()
                .baseUrl(descriptor.endpoint())
                .apiKey(apiKey)
                .modelName(request.model().modelId())
                .timeout(descriptor.modelCallTimeoutPolicy().modelCallTimeout());
        // SDK builder 不接受 null promptCacheKey，因此仅在非 NONE 时显式设置。
        if (key != null) {
          builder.promptCacheKey(key);
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        OpenAiProviderAdapter.requireProviderType(request, providerType());
        // 校验与键解析与 chatModel 完全一致；幂等并提前抛错，避免在 stub 模型已建立后才 fail。
        CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
      }
    };
  }
}
