package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

/**
 * OpenAI 兼容协议 provider。
 *
 * 实现特点：
 * - 使用 OpenAiStreamingChatModel。
 * - 额外补充 OpenAI inputTokensDetails.cachedTokens -> cacheReadTokens 映射。
 *
 * @author fengwk
 */
public class OpenAiModelProvider extends AbstractModelProvider {

    /**
     * 使用给定连接配置创建 OpenAI 兼容 provider。
     */
    protected OpenAiModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    /**
     * 构造 OpenAI 兼容协议对应的底层 StreamingChatModel。
     */
    @Override
    protected StreamingChatModel getChatModel(ModelInfo modelInfo,
                                              Variant variant) {
        return OpenAiStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

    /**
     * 在通用 metadata 基础上补充 OpenAI cachedTokens -> cacheReadTokens 映射。
     */
    @Override
    protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
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
