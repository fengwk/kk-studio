package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;

/**
 * OpenAI Responses API provider。
 *
 * <p>与 OpenAI 兼容协议 provider 的差异： - 底层模型实现不同，使用 OpenAiOfficialResponsesStreamingChatModel。 -
 * 当前未额外补充专有 metadata 映射，沿用 AbstractModelProvider 默认实现。
 *
 * @author fengwk
 */
public class OpenAiResponseModelProvider extends AbstractModelProvider {

  /** 使用给定连接配置创建 OpenAI Responses provider。 */
  protected OpenAiResponseModelProvider(ProviderInfo providerInfo) {
    super(providerInfo);
  }

  /** 构造 OpenAI Responses API 对应的底层 StreamingChatModel。 */
  @Override
  protected StreamingChatModel getChatModel(ModelInfo modelInfo, Variant variant) {
    return OpenAiOfficialResponsesStreamingChatModel.builder()
        .baseUrl(getProviderInfo().getBaseUrl())
        .apiKey(getProviderInfo().getApiKey())
        .modelName(modelInfo.getName())
        .timeout(getProviderInfo().getTimeout())
        .build();
  }
}
