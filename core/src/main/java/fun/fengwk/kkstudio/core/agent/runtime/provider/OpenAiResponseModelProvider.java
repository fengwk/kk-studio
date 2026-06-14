package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatRequestParameters;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;

import java.util.List;

/**
 * @author fengwk
 */
public class OpenAiResponseModelProvider extends AbstractModelProvider {

    protected OpenAiResponseModelProvider(ProviderConfig providerConfig) {
        super(providerConfig);
    }

    @Override
    public ChatRequest buildChatRequest(List<ChatMessage> messageList, ModelRequestConfig modelConfig) {
        OpenAiOfficialResponsesChatRequestParameters.Builder parametersBuilder =
            OpenAiOfficialResponsesChatRequestParameters.builder();
        applyCommonParameters(parametersBuilder, modelConfig);
        OpenAiOfficialResponsesChatRequestParameters parameters = parametersBuilder.build();
        return ChatRequest.builder()
            .messages(messageList)
            .parameters(parameters)
            .build();
    }

    @Override
    public StreamingChatModel getChatModel() {
        return OpenAiOfficialResponsesStreamingChatModel.builder()
            .baseUrl(getProviderConfig().getBaseUrl())
            .apiKey(getProviderConfig().getApiKey())
            .build();
    }

}
