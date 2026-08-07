package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Map;
import java.util.Objects;

/** Anthropic Provider 适配器。 */
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
    return new LangChainModelProvider(providerType()) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        CacheRequestValidator.AnthropicCacheFlags flags =
            CacheRequestValidator.requireAnthropicBreakpoints(request.cacheControl());
        AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder =
            AnthropicStreamingChatModel.builder()
                .baseUrl(descriptor.endpoint())
                .apiKey(apiKey)
                .timeout(descriptor.modelCallTimeoutPolicy().modelCallTimeout())
                .returnThinking(true)
                .cacheSystemMessages(flags.cacheSystemMessages())
                .cacheTools(flags.cacheTools());
        String reasoningEffort =
            request.model().reasoning() ? request.variant().reasoningEffort() : null;
        if (reasoningEffort != null) {
          builder
              .thinkingType("adaptive")
              .customParameters(Map.of("output_config", Map.of("effort", reasoningEffort)));
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        // 提前执行 BREAKPOINTS 校验，确保非法 control 在 chatModel 之前 fail。
        CacheRequestValidator.requireAnthropicBreakpoints(request.cacheControl());
      }
    };
  }
}
