package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;

/**
 * @author fengwk
 */
public class GoogleModelProvider extends AbstractModelProvider {

    protected GoogleModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

}
