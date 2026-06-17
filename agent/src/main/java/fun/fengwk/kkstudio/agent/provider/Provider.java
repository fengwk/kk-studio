package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.data.message.ChatMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;

import java.util.List;

/**
 * Provider 表示模型供应商的运行时调用边界。
 *
 * 语义说明：
 * - Provider 对外只暴露一个稳定入口：asyncChat(...)
 * - Agent 侧只关心 ModelInfo、Variant、ToolInfo 与流式回调，不关心具体 SDK 类型
 * - 若接入新的供应商，优先继承 AbstractModelProvider 复用通用流式编排逻辑
 * - 仅当底层 SDK 的调用模型与 AbstractModelProvider 明显不匹配时，才直接实现本接口
 *
 * @author fengwk
 */
public interface Provider {

    /**
     * 返回当前 provider 的类型标识。
     */
    ProviderType getProviderType();

    /**
     * 发起一次异步 assistant 调用。
     *
     * 调用约束：
     * - chatMessageList 是本轮重放后的完整上下文
     * - modelInfo 提供模型稳定信息
     * - variant 提供本次实际请求参数
     * - toolInfos 提供本次允许调用的工具描述
     * - handler 负责接收流式 text/thinking/toolcall/complete/error 回调
     */
    AssistantResponseHandle asyncChat(List<ChatMessage> chatMessageList,
                                      ModelInfo modelInfo,
                                      Variant variant,
                                      List<ToolInfo> toolInfos,
                                      AssistantResponseHandler handler);

}
