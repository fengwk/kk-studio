package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/** 一个 Turn 所需的 Provider 请求和本轮允许调用的工具描述。 */
public record AgentTurnRequest(ProviderRequest providerRequest, List<ToolDescriptor> tools) {

  public AgentTurnRequest {
    providerRequest = Objects.requireNonNull(providerRequest, "providerRequest");
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }
}
