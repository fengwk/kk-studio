package fun.fengwk.kkstudio.platform.harness.model.provider;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** OpenAI Responses API Provider 适配器。 */
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
        Duration timeout = descriptor.modelCallTimeoutPolicy().modelCallTimeout();
        JdkHttpClientBuilder httpClientBuilder = JdkHttpClient.builder();
        if (timeout != null) {
          httpClientBuilder.connectTimeout(timeout).readTimeout(timeout);
        }
        OpenAiResponsesStreamingChatModel.Builder builder =
            OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(descriptor.endpoint())
                .apiKey(apiKey)
                .modelName(request.model().modelName())
                .store(false)
                .strictTools(true)
                .strictJsonSchema(true);
        String reasoningEffort =
            request.model().reasoning() ? request.variant().reasoningEffort() : null;
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
          builder.reasoningEffort(reasoningEffort.trim().toLowerCase(Locale.ROOT));
        }
        if (key != null) {
          builder.promptCacheKey(key);
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        // 校验与键解析与 chatModel 完全一致；幂等并提前抛错，避免在 stub 模型已建立后才 fail。
        CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
      }
    };
  }
}
