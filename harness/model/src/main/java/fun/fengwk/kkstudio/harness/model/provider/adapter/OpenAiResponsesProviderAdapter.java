package fun.fengwk.kkstudio.harness.model.provider.adapter;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
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
    return new LangChainModelProvider() {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        return OpenAiOfficialResponsesStreamingChatModel.builder()
            .baseUrl(descriptor.endpoint())
            .apiKey(apiKey)
            .modelName(request.model().modelId())
            .timeout(descriptor.timeout())
            .build();
      }
    };
  }
}
