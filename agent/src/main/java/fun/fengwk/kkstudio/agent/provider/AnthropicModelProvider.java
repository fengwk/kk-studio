package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * @author fengwk
 */
public class AnthropicModelProvider extends AbstractModelProvider {

    protected AnthropicModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return AnthropicStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

}
