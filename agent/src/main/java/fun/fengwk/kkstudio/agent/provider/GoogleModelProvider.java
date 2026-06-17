package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;

/**
 * Google Gemini provider。
 *
 * 实现特点：
 * - 使用 GoogleAiGeminiStreamingChatModel。
 * - 当前仅依赖通用参数映射，未补充额外专有 metadata 字段。
 *
 * @author fengwk
 */
public class GoogleModelProvider extends AbstractModelProvider {

    /**
     * 使用给定连接配置创建 Google Gemini provider。
     */
    protected GoogleModelProvider(ProviderInfo providerInfo) {
        super(providerInfo);
    }

    /**
     * 构造 Google Gemini 对应的底层 StreamingChatModel。
     */
    @Override
    protected StreamingChatModel getChatModel(ModelInfo modelInfo,
                                              Variant variant) {
        return GoogleAiGeminiStreamingChatModel.builder()
            .baseUrl(getProviderInfo().getBaseUrl())
            .apiKey(getProviderInfo().getApiKey())
            .timeout(getProviderInfo().getTimeout())
            .returnThinking(true)
            .build();
    }

}
