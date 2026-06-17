package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.data.message.ChatMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;

import java.util.List;

/**
 * Provider 表示模型供应商的运行时调用边界。
 *
 * @author fengwk
 */
public interface Provider {

    ProviderType getProviderType();

    AssistantResponseHandle asyncChat(List<ChatMessage> chatMessageList,
                                      ModelInfo modelInfo,
                                      Variant variant,
                                      List<ToolInfo> toolInfos,
                                      AssistantResponseHandler handler);

}
