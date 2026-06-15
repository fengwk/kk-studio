package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

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

    @Override
    public AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
        AssistantMetadata assistantMetadata = super.toAssistantMetadata(metadata);
        if (assistantMetadata == null || metadata == null || !(metadata.tokenUsage() instanceof OpenAiTokenUsage tokenUsage)) {
            return assistantMetadata;
        }
        if (tokenUsage.inputTokensDetails() == null) {
            return assistantMetadata;
        }
        AssistantUsage usage = assistantMetadata.getUsage();
        if (usage == null) {
            usage = new AssistantUsage();
            assistantMetadata.setUsage(usage);
        }
        usage.setCacheReadTokens(tokenUsage.inputTokensDetails().cachedTokens());
        return assistantMetadata;
    }

}
