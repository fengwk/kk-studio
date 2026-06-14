package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;

import java.lang.reflect.Method;

/**
 * AssistantMetadataMapper 负责将 LangChain4j metadata 转为持久化元信息。
 *
 * @author fengwk
 */
public class AssistantMetadataMapper {

    public AssistantMetadata from(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        AssistantMetadata assistantMetadata = new AssistantMetadata();
        assistantMetadata.setId(metadata.id());
        assistantMetadata.setModelName(metadata.modelName());
        assistantMetadata.setFinishReason(metadata.finishReason() == null ? null : metadata.finishReason().name());
        assistantMetadata.setUsage(toAssistantUsage(metadata.tokenUsage()));
        return assistantMetadata;
    }

    private AssistantUsage toAssistantUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        AssistantUsage usage = new AssistantUsage();
        usage.setInputTokens(tokenUsage.inputTokenCount());
        usage.setOutputTokens(tokenUsage.outputTokenCount());
        usage.setTotalTokens(tokenUsage.totalTokenCount());
        usage.setCacheReadTokens(readInteger(tokenUsage, "cacheReadInputTokens", "cachedContentTokenCount", "cacheReadTokens"));
        usage.setCacheWriteTokens(readInteger(tokenUsage, "cacheCreationInputTokens", "cacheWriteTokens"));
        return usage;
    }

    private Integer readInteger(TokenUsage tokenUsage, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = tokenUsage.getClass().getMethod(methodName);
                Object result = method.invoke(tokenUsage);
                if (result instanceof Integer value) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
                // 当前 tokenUsage 未声明该字段时继续尝试其它候选方法。
            }
        }
        return null;
    }

}
