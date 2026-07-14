package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 当前 Runtime Snapshot 解析出的单 Turn 资源；模型只接收 toolDescriptors。 */
public record TurnResources(
    ModelProvider provider,
    ModelDescriptor model,
    ModelVariant variant,
    List<ToolBinding> toolBindings,
    Path workdir,
    Path workspaceRoot) {
  public TurnResources {
    provider = Objects.requireNonNull(provider, "provider");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    Set<String> names = new HashSet<>();
    if (toolBindings.stream().anyMatch(binding -> !names.add(binding.descriptor().name()))) {
      throw new IllegalArgumentException("tool binding names must be unique");
    }
    workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    workspaceRoot =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
  }

  public List<ToolDescriptor> toolDescriptors() {
    return toolBindings.stream().map(ToolBinding::descriptor).toList();
  }
}
