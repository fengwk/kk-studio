package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

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
        return builder;
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
