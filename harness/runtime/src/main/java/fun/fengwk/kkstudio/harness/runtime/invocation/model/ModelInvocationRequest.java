package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Model invocation 的完整冻结请求。
 *
 * <p>实际 Environment 路由（{@code environmentName}）是该请求的唯一路由，Thread YOLO policy （{@code
 * yoloEnabled}）也在这里冻结；后续的 Environment 下线、branch rebinding、relocation 或 {@code SET_YOLO} 都不会改变已存在的
 * invocation。{@code providerRequest.tools()} 必须与有序的 {@code toolBindings} descriptor 一一对应，tool 和
 * skill binding 名称不能重复，并且每个 environment-bound tool 或 skill 必须引用正好是该请求的路由。
 */
public record ModelInvocationRequest(
    EnvironmentName environmentName,
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
    requireConsistentRoutes(environmentName, toolBindings, skillBindings);
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
      EnvironmentName environmentName,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings) {
    for (ToolBinding binding : toolBindings) {
      if (binding.type() == ToolType.ENVIRONMENT
          && !Objects.equals(environmentName, binding.environmentName())) {
        throw new IllegalArgumentException(
            "tool binding environment route must match request environmentName");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironmentName() != null
          && !skill.sourceEnvironmentName().equals(environmentName)) {
        throw new IllegalArgumentException(
            "skill source environment must match request environmentName");
      }
    }
  }
}
