package fun.fengwk.kkstudio.harness.model.provider.adapter;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import java.util.Objects;

/** Anthropic Provider adapter。 */
public final class AnthropicProviderAdapter implements ProviderAdapter {

  private final String apiKey;

  public AnthropicProviderAdapter(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.ANTHROPIC;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    OpenAiProviderAdapter.requireType(descriptor, providerType());
    return new LangChainModelProvider() {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        CacheRequestValidator.AnthropicCacheFlags flags =
            CacheRequestValidator.requireAnthropicBreakpoints(request.cacheControl());
        return AnthropicStreamingChatModel.builder()
            .baseUrl(descriptor.endpoint())
            .apiKey(apiKey)
            .timeout(descriptor.timeout())
            .returnThinking(true)
            .cacheSystemMessages(flags.cacheSystemMessages())
            .cacheTools(flags.cacheTools())
            .build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        OpenAiProviderAdapter.requireProviderType(request, providerType());
        // 提前执行 BREAKPOINTS 校验，确保非法 control 在 chatModel 之前 fail。
        CacheRequestValidator.requireAnthropicBreakpoints(request.cacheControl());
      }
    };
  }
}
