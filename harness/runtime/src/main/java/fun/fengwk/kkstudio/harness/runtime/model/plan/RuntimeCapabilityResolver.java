package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;

import java.util.List;

/** Resolves the live descriptors represented by a compact runtime config for one model request. */
@FunctionalInterface
public interface RuntimeCapabilityResolver {

  ResolvedCapabilities resolve(RuntimeConfigSnapshot config);

  record ResolvedCapabilities(List<ToolBinding> toolBindings, List<SkillSnapshot> skillSnapshots) {
    public ResolvedCapabilities {
      toolBindings = List.copyOf(toolBindings);
      skillSnapshots = List.copyOf(skillSnapshots);
    }
  }
}
