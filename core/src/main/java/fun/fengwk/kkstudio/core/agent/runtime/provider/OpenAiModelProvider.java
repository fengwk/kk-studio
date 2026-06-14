package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.List;

/**
 * @author fengwk
 */
public class OpenAiModelProvider extends AbstractModelProvider {

    protected OpenAiModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public ChatRequest buildChatRequest(List<ChatMessage> messageList, ModelRequestConfig modelConfig) {
        OpenAiChatRequestParameters.Builder parametersBuilder = OpenAiChatRequestParameters.builder();
        applyCommonParameters(parametersBuilder, modelConfig);
        OpenAiChatRequestParameters parameters = parametersBuilder.build();
        return ChatRequest.builder()
            .messages(messageList)
            .parameters(parameters)
            .build();
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
