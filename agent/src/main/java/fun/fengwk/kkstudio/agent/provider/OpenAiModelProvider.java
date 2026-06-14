package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * @author fengwk
 */
public class OpenAiModelProvider extends AbstractModelProvider {

    protected OpenAiModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    @Override
    public StreamingChatModel getChatModel() {
        return OpenAiStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

}
