package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ephemeral live execution value resolved for one provider query.
 *
 * <p>It is never a Thread or Entry configuration value. The durable copy is the fully materialized
 * {@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest}.
 */
public record ResolvedTurnExecution(
    String systemPrompt,
    ModelDescriptor model,
    ModelVariant variant,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {

  public ResolvedTurnExecution {
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    toolBindings = copyUniqueTools(toolBindings);
    skillBindings = copyUniqueSkills(skillBindings);
    if ((!toolBindings.isEmpty() || !skillBindings.isEmpty()) && !model.tools()) {
      throw new IllegalArgumentException(
          "model.tools must be true when tools or skills are resolved");
    }
  }

  private static List<ToolBinding> copyUniqueTools(List<ToolBinding> source) {
    Objects.requireNonNull(source, "toolBindings");
    Set<String> names = new HashSet<>();
    for (ToolBinding binding : source) {
      Objects.requireNonNull(binding, "toolBindings[]");
      if (!names.add(binding.descriptor().name())) {
        throw new IllegalArgumentException(
            "toolBindings contains duplicate name: " + binding.descriptor().name());
      }
    }
    return List.copyOf(source);
  }

  private static List<SkillBinding> copyUniqueSkills(List<SkillBinding> source) {
    Objects.requireNonNull(source, "skillBindings");
    Set<String> names = new HashSet<>();
    for (SkillBinding binding : source) {
      Objects.requireNonNull(binding, "skillBindings[]");
      if (!names.add(binding.name())) {
        throw new IllegalArgumentException(
            "skillBindings contains duplicate name: " + binding.name());
      }
    }
    return List.copyOf(source);
  }
}
