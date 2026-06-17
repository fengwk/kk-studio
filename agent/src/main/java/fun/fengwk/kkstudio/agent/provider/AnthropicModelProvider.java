package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

/**
 * @author fengwk
 */
public class AnthropicModelProvider extends AbstractModelProvider {

    protected AnthropicModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    @Override
    protected StreamingChatModel getChatModel() {
        return AnthropicStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

    @Override
    protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
        AssistantMetadata assistantMetadata = super.toAssistantMetadata(metadata);
        if (assistantMetadata == null || metadata == null || !(metadata.tokenUsage() instanceof AnthropicTokenUsage tokenUsage)) {
            return assistantMetadata;
        }
        AssistantUsage usage = assistantMetadata.getUsage();
        if (usage == null) {
            usage = new AssistantUsage();
            assistantMetadata.setUsage(usage);
        }
        usage.setCacheReadTokens(tokenUsage.cacheReadInputTokens());
        usage.setCacheWriteTokens(tokenUsage.cacheCreationInputTokens());
        return assistantMetadata;
    }

}
