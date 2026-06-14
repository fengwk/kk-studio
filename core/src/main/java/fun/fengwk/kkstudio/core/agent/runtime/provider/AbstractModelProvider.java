package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;

import java.util.List;
import java.util.Objects;

/**
 * @author fengwk
 */
public abstract class AbstractModelProvider implements Provider {

    private final ProviderConfig providerConfig;

    protected AbstractModelProvider(ProviderConfig providerConfig) {
        this.providerConfig = Objects.requireNonNull(providerConfig, "providerConfig must not be null");
    }

    protected ProviderConfig getProviderConfig() {
        return providerConfig;
    }

    protected ChatRequest.Builder setParameters(ChatRequest.Builder builder, ModelRequestConfig modelConfig) {
        DefaultChatRequestParameters.Builder<?> parametersBuilder = DefaultChatRequestParameters.builder();
        applyCommonParameters(parametersBuilder, modelConfig);
        return builder.parameters(parametersBuilder.build());
    }

    protected void applyCommonParameters(DefaultChatRequestParameters.Builder<?> builder, ModelRequestConfig modelConfig) {
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

    @Override
    public ChatRequest buildChatRequest(List<ChatMessage> messageList, ModelRequestConfig modelConfig) {
        ChatRequest.Builder builder = ChatRequest.builder()
            .modelName(modelConfig.getModelName())
            .messages(messageList);
        return setParameters(builder, modelConfig).build();
    }

    @Override
    public ProviderType getProviderType() {
        return getProviderConfig().getProviderType();
    }

}
