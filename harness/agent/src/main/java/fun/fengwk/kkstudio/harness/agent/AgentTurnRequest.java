package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * 一个 Turn 的 Provider 无关输入。
 *
 * <p>Turn Engine 根据 tools 将其转换为包含 {@code ProviderToolDefinition} 的 ProviderRequest，避免公共 API
 * 同时暴露两份等价工具声明。
 */
public record AgentTurnRequest(
    ModelDescriptor model,
    ModelVariant variant,
    List<ProviderMessage> messages,
    List<ToolDescriptor> tools) {

  public AgentTurnRequest {
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }
}
