package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialStreamingChatModel;

/**
 * @author fengwk
 */
public class OpenAiResponseModelProvider extends AbstractModelProvider {

    protected OpenAiResponseModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return OpenAiOfficialStreamingChatModel.builder()
            .baseUrl(getProviderConfig().getBaseUrl())
            .apiKey(getProviderConfig().getApiKey())
            .build();
    }

}
