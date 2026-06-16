package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
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
        DefaultChatRequestParameters.Builder<?> parametersBuilder,
        ModelInfo modelInfo,
        Variant variant,
        List<ToolSpecification> toolSpecifications) {
        return parametersBuilder;
    }

    @Override
    public ChatRequest buildChatRequest(List<ChatMessage> chatMessageList,
                                        ModelInfo modelInfo,
                                        Variant variant,
                                        List<ToolSpecification> toolSpecifications) {
        ChatRequest.Builder builder = ChatRequest.builder()
            .modelName(modelInfo.getName())
            .messages(chatMessageList);

        DefaultChatRequestParameters.Builder<?> parametersBuilder = newParametersBuilder();
        applyCommonParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
        setParameters(parametersBuilder, modelInfo, variant, toolSpecifications);
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

    private void applyCommonParameters(DefaultChatRequestParameters.Builder<?> builder,
                                       ModelInfo modelInfo,
                                       Variant variant,
                                       List<ToolSpecification> toolSpecifications) {
        builder
            .modelName(modelInfo.getName())
            .temperature(variant.getTemperature())
            .topP(variant.getTopP())
            .topK(variant.getTopK())
            .frequencyPenalty(variant.getFrequencyPenalty())
            .presencePenalty(variant.getPresencePenalty())
            .maxOutputTokens(variant.getMaxOutputTokens())
            .stopSequences(variant.getStopSequences())
            .toolSpecifications(toolSpecifications);
    }

}
