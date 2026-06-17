package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;

/**
 * @author fengwk
 */
public class OpenAiResponseModelProvider extends AbstractModelProvider {

    protected OpenAiResponseModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    @Override
    protected StreamingChatModel getChatModel() {
        return OpenAiOfficialResponsesStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .build();
    }

}
