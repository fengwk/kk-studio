package fun.fengwk.kkstudio.harness.model.provider;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import java.util.List;
import java.util.Objects;

/** 一次 Provider 流请求。 */
public record ProviderRequest(
    ModelDescriptor model,
    ModelVariant variant,
    List<ProviderMessage> messages,
    List<ProviderToolDefinition> tools) {

  public ProviderRequest {
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }
}
