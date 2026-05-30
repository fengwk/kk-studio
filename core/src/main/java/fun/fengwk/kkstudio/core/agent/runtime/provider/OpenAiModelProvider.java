package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * @author fengwk
 */
public class OpenAiModelProvider extends AbstractModelProvider {

    protected OpenAiModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return OpenAiStreamingChatModel.builder()
            .baseUrl(getProviderConfig().getBaseUrl())
            .apiKey(getProviderConfig().getApiKey())
            .returnThinking(true)
            .build();
    }

}
