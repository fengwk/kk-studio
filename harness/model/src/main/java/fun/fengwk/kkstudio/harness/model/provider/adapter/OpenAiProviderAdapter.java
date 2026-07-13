package fun.fengwk.kkstudio.harness.model.provider.adapter;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** OpenAI 兼容 Chat Completions Provider adapter。 */
public final class OpenAiProviderAdapter implements ProviderAdapter {

  private final String apiKey;

  public OpenAiProviderAdapter(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    requireType(descriptor, providerType());
    return new LangChainModelProvider() {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
            OpenAiStreamingChatModel.builder()
                .baseUrl(descriptor.endpoint())
                .apiKey(apiKey)
                .modelName(request.model().modelId())
                .timeout(descriptor.timeout())
                .returnThinking(true);
        if (isMiniMaxEndpoint(descriptor.endpoint())) {
          builder.customParameters(Map.of("reasoning_split", true));
        }
        return builder.build();
      }

      @Override
      protected boolean extractsThinkTags() {
        return isMiniMaxEndpoint(descriptor.endpoint());
      }
    };
  }

  private static boolean isMiniMaxEndpoint(String endpoint) {
    try {
      String host = URI.create(endpoint).getHost();
      return host != null && host.toLowerCase(Locale.ROOT).contains("minimax");
    } catch (IllegalArgumentException ignored) {
      return endpoint.toLowerCase(Locale.ROOT).contains("minimax");
    }
  }

  static void requireType(ProviderDescriptor descriptor, ProviderType expected) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != expected) {
      throw new IllegalArgumentException("provider descriptor type does not match adapter");
    }
  }
}
