package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

/**
 * Anthropic provider。
 *
 * <p>实现特点： - 使用 AnthropicStreamingChatModel。 - 额外补充 Anthropic cacheRead/cacheCreation token 映射。
 *
 * @author fengwk
 */
public class AnthropicModelProvider extends AbstractModelProvider {

  /** 使用给定连接配置创建 Anthropic provider。 */
  protected AnthropicModelProvider(ProviderInfo providerInfo) {
    super(providerInfo);
  }

  /** 构造 Anthropic 对应的底层 StreamingChatModel。 */
  @Override
  protected StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant) {
    return AnthropicStreamingChatModel.builder()
        .baseUrl(getProviderInfo().getBaseUrl())
        .apiKey(getProviderInfo().getApiKey())
        .timeout(getProviderInfo().getTimeout())
        .returnThinking(true)
        .build();
  }

  /** 在通用 metadata 基础上补充 Anthropic cache token 映射。 */
  @Override
  protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
    AssistantMetadata assistantMetadata = super.toAssistantMetadata(metadata);
    if (assistantMetadata == null
        || metadata == null
        || !(metadata.tokenUsage() instanceof AnthropicTokenUsage tokenUsage)) {
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
