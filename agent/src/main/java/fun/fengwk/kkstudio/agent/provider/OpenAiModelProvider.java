package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 兼容协议 provider。
 *
 * <p>实现特点： - 使用 OpenAiStreamingChatModel。 - 额外补充 OpenAI inputTokensDetails.cachedTokens ->
 * cacheReadTokens 映射。
 *
 * @author fengwk
 */
public class OpenAiModelProvider extends AbstractModelProvider {

  private static final Map<String, Object> MINIMAX_REASONING_SPLIT =
      Map.of("reasoning_split", true);

  /** 使用给定连接配置创建 OpenAI 兼容 provider。 */
  protected OpenAiModelProvider(ProviderInfo providerInfo) {
    super(providerInfo);
  }

  /** 构造 OpenAI 兼容协议对应的底层 StreamingChatModel。 */
  @Override
  protected StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant) {
    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
        OpenAiStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .modelName(modelInfo.getName())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true);
    if (isMiniMaxCompatibleEndpoint()) {
      builder.customParameters(MINIMAX_REASONING_SPLIT);
    }
    return builder.build();
  }

  @Override
  public AssistantResponseHandle asyncChat(
      List<AgentMessage> messages,
      ModelInfo modelInfo,
      Variant variant,
      List<ToolInfo> toolInfos,
      AssistantResponseHandler handler) {
    AssistantResponseHandler effectiveHandler =
        isMiniMaxCompatibleEndpoint()
            ? new ThinkTagExtractingAssistantResponseHandler(handler)
            : handler;
    return super.asyncChat(messages, modelInfo, variant, toolInfos, effectiveHandler);
  }

  /** 在通用 metadata 基础上补充 OpenAI cachedTokens -> cacheReadTokens 映射。 */
  @Override
  protected AssistantMetadata toAssistantMetadata(ChatResponseMetadata metadata) {
    AssistantMetadata assistantMetadata = super.toAssistantMetadata(metadata);
    if (assistantMetadata == null
        || metadata == null
        || !(metadata.tokenUsage() instanceof OpenAiTokenUsage tokenUsage)) {
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

  /**
   * MiniMax M2.x 的 OpenAI 兼容接口默认会把 thinking 混入 content。
   *
   * <p>通过 reasoning_split=true 可以把推理内容拆到独立字段，避免正文泄漏给用户。
   */
  private boolean isMiniMaxCompatibleEndpoint() {
    String baseUrl = getProviderInfo().getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      String host = URI.create(baseUrl).getHost();
      return host != null && host.toLowerCase(Locale.ROOT).contains("minimax");
    } catch (IllegalArgumentException ignored) {
      return baseUrl.toLowerCase(Locale.ROOT).contains("minimax");
    }
  }
}
