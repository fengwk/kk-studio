package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.List;
import java.util.Objects;

/**
 * Complete frozen request of one Model invocation.
 *
 * <p>The actual Environment route ({@code environmentId}) is the single route of this request and
 * the Thread YOLO policy ({@code yoloEnabled}) is frozen here; later Environment offline, branch
 * rebinding, relocation or {@code SET_YOLO} never change an existing invocation. {@code
 * providerRequest.tools()} must map one-to-one onto the ordered {@code toolBindings} descriptors,
 * tool and skill binding names must not repeat, and every environment-bound tool or skill must
 * reference exactly this request route.
 */
public record ModelInvocationRequest(
    EnvironmentId environmentId,
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {

  public ModelInvocationRequest {
    if (providerRequest == null) {
      throw new IllegalArgumentException("providerRequest must not be null");
    }
    toolBindings = List.copyOf(toolBindings);
    skillBindings = List.copyOf(skillBindings);
    requireOneToOneProviderTools(providerRequest, toolBindings);
    requireUniqueToolNames(toolBindings);
    requireUniqueSkillNames(skillBindings);
    requireConsistentRoutes(environmentId, toolBindings, skillBindings);
  }

  private static void requireOneToOneProviderTools(
      ProviderRequest providerRequest, List<ToolBinding> toolBindings) {
    if (providerRequest.tools().size() != toolBindings.size()) {
      throw new IllegalArgumentException("provider tools must map one-to-one onto tool bindings");
    }
    for (int i = 0; i < toolBindings.size(); i++) {
      String providerName = providerRequest.tools().get(i).name();
      String bindingName = toolBindings.get(i).descriptor().name();
      if (!providerName.equals(bindingName)) {
        throw new IllegalArgumentException(
            "provider tool at index "
                + i
                + " does not match binding descriptor: "
                + providerName
                + " != "
                + bindingName);
      }
    }
  }

  private static void requireUniqueToolNames(List<ToolBinding> toolBindings) {
    for (int i = 0; i < toolBindings.size(); i++) {
      String name = toolBindings.get(i).descriptor().name();
      for (int j = i + 1; j < toolBindings.size(); j++) {
        if (name.equals(toolBindings.get(j).descriptor().name())) {
          throw new IllegalArgumentException("tool binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireUniqueSkillNames(List<SkillBinding> skillBindings) {
    for (int i = 0; i < skillBindings.size(); i++) {
      String name = skillBindings.get(i).name();
      for (int j = i + 1; j < skillBindings.size(); j++) {
        if (name.equals(skillBindings.get(j).name())) {
          throw new IllegalArgumentException("skill binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireConsistentRoutes(
      EnvironmentId environmentId,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings) {
    for (ToolBinding binding : toolBindings) {
      if (binding.type() == ToolType.ENVIRONMENT
          && !Objects.equals(environmentId, binding.environmentId())) {
        throw new IllegalArgumentException(
            "tool binding environment route must match request environmentId");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironmentId() != null
          && !skill.sourceEnvironmentId().equals(environmentId)) {
        throw new IllegalArgumentException(
            "skill source environment must match request environmentId");
      }
    }
  }
}
