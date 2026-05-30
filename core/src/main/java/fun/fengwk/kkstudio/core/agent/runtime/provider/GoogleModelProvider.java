package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;

/**
 * @author fengwk
 */
public class GoogleModelProvider extends AbstractModelProvider {

    protected GoogleModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
            .baseUrl(getProviderConfig().getBaseUrl())
            .apiKey(getProviderConfig().getApiKey())
            .returnThinking(true)
            .build();
    }

}
