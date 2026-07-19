package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 一次 Turn 解析出的不可变资源快照。 */
public record TurnResources(
    ModelProvider provider,
    ModelCallTimeoutPolicy modelCallTimeoutPolicy,
    ModelDescriptor model,
    ModelVariant variant,
    List<ToolDescriptor> toolDescriptors,
    List<ToolBinding> toolBindings,
    Path workdir,
    Path environmentRoot) {

  public TurnResources {
    provider = Objects.requireNonNull(provider, "provider");
    modelCallTimeoutPolicy =
        Objects.requireNonNull(modelCallTimeoutPolicy, "modelCallTimeoutPolicy");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    toolDescriptors = List.copyOf(Objects.requireNonNull(toolDescriptors, "toolDescriptors"));
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    workdir = Objects.requireNonNull(workdir, "workdir");
    environmentRoot = Objects.requireNonNull(environmentRoot, "environmentRoot");
  }
}
