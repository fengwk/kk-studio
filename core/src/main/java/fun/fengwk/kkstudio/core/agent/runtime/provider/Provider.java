package fun.fengwk.kkstudio.core.agent.runtime.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;

/**
 * @author fengwk
 */
public interface Provider {

    ProviderType getProviderType();

    ChatRequest buildChatRequest(List<ChatMessage> messageList, ModelRequestConfig modelConfig);

    StreamingChatModel getChatModel();

}
