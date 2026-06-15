package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;

import java.util.List;
import java.util.Objects;

/**
 * @author fengwk
 */
public abstract class AbstractModelProvider implements Provider {

    private final ProviderInfo providerInfo;

    protected AbstractModelProvider(ProviderInfo providerInfo) {
        this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo must not be null");
    }

    protected ProviderInfo getProviderInfo() {
        return providerInfo;
    }

    protected DefaultChatRequestParameters.Builder<?> newParametersBuilder() {
        return DefaultChatRequestParameters.builder();
    }

    protected DefaultChatRequestParameters.Builder<?> setParameters(
        DefaultChatRequestParameters.Builder<?> parametersBuilder, ModelRequestConfig modelConfig) {
        return parametersBuilder;
    }

    @Override
    public ChatRequest buildChatRequest(List<ChatMessage> chatMessageList, ModelRequestConfig modelConfig) {
        ChatRequest.Builder builder = ChatRequest.builder()
            .modelName(modelConfig.getModelName())
            .messages(chatMessageList);

        DefaultChatRequestParameters.Builder<?> parametersBuilder = newParametersBuilder();
        applyCommonParameters(parametersBuilder, modelConfig);
        setParameters(parametersBuilder, modelConfig);
        builder.parameters(parametersBuilder.build());

        return builder.build();
    }

    @Override
    public ProviderType getProviderType() {
        return getProviderInfo().getProviderType();
    }

    @Override
    public AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
        return Provider.toCommonAssistantMetadata(metadata);
    }

    private void applyCommonParameters(DefaultChatRequestParameters.Builder<?> builder, ModelRequestConfig modelConfig) {
        builder
            .modelName(modelConfig.getModelName())
            .temperature(modelConfig.getTemperature())
            .topP(modelConfig.getTopP())
            .topK(modelConfig.getTopK())
            .frequencyPenalty(modelConfig.getFrequencyPenalty())
            .presencePenalty(modelConfig.getPresencePenalty())
            .maxOutputTokens(modelConfig.getMaxOutputTokens())
            .stopSequences(modelConfig.getStopSequences())
            .toolSpecifications(modelConfig.getToolSpecifications())
            .toolChoice(modelConfig.getToolChoice())
            .responseFormat(modelConfig.getResponseFormat());
    }

}
