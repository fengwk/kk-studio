package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/** 当前 Runtime Snapshot 解析出的单 Turn 资源。 */
public record TurnResources(
    ModelProvider provider,
    ModelDescriptor model,
    ModelVariant variant,
    List<ToolDescriptor> tools) {
  public TurnResources {
    provider = Objects.requireNonNull(provider, "provider");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }
}
