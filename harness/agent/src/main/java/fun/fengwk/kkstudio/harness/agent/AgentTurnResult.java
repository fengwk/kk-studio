package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.util.List;
import java.util.Objects;

/** 一个完整 Assistant Turn 的 Provider 响应与已验证工具调用。 */
public record AgentTurnResult(ProviderResponse response, List<ToolCall> toolCalls) {

  public AgentTurnResult {
    response = Objects.requireNonNull(response, "response");
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
  }
}
