package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * @author fengwk
 */
public class AnthropicModelProvider extends AbstractModelProvider {

    protected AnthropicModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return AnthropicStreamingChatModel.builder()
            .baseUrl(getProviderConfig().getBaseUrl())
            .apiKey(getProviderConfig().getApiKey())
            .returnThinking(true)
            .build();
    }

}
