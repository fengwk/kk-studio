package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.List;
import java.util.Objects;

/** 一个完整 Assistant Turn 的最终语义消息、Provider 用量快照和最终实际发送的请求。 */
public record AgentTurnResult(
    AgentAssistantMessage assistantMessage,
    ProviderResponse providerResponse,
    ProviderRequest providerRequest) {

  public AgentTurnResult {
    assistantMessage = Objects.requireNonNull(assistantMessage, "assistantMessage");
    providerResponse = Objects.requireNonNull(providerResponse, "providerResponse");
    providerRequest = Objects.requireNonNull(providerRequest, "providerRequest");
  }

  /** 返回已通过名称、唯一性和输入 schema 校验的工具调用。 */
  public List<ToolCall> toolCalls() {
    return assistantMessage.toolCalls();
  }
}
