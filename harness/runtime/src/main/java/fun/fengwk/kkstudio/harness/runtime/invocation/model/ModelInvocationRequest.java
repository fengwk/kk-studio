package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Model invocation 的完整冻结请求。
 *
 * <p>实际 Environment 绑定（{@code environment}，完整 binding，可为 null）是该请求的唯一路由，Thread YOLO policy （{@code
 * yoloEnabled}）也在这里冻结；后续的 Environment 下线、branch rebinding、relocation 或 {@code SET_YOLO} 都不会改变已存在的
 * invocation。{@code providerRequest.tools()} 必须与有序的 {@code toolBindings} descriptor 一一对应，tool 和
 * skill binding 名称不能重复，并且每个 environment-bound tool 或 skill 必须引用正好是该请求的 binding。{@code
 * contextWindow} 是冻结的正上下文窗口（来自 model config limit.context），触发与规划压缩都以此为准；{@code subagentBindings}
 * 冻结本次调用可委派的 Agent allowlist。{@code compaction} 非空表示这是一次压缩调用（不得携带任何 tool/skill/subagent
 * binding），null 表示正常调用。
 */
public record ModelInvocationRequest(
    EnvironmentBinding environment,
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    boolean yoloEnabled,
    int contextWindow,
    CompactionRequest compaction) {

  /** 构造不具备子 Agent 委派能力的请求。 */
  public ModelInvocationRequest(
      EnvironmentBinding environment,
      ProviderRequest providerRequest,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      boolean yoloEnabled,
      int contextWindow,
      CompactionRequest compaction) {
    this(
        environment,
        providerRequest,
        toolBindings,
        skillBindings,
        List.of(),
        yoloEnabled,
        contextWindow,
        compaction);
  }

  public ModelInvocationRequest {
    if (providerRequest == null) {
      throw new IllegalArgumentException("providerRequest must not be null");
    }
    toolBindings = List.copyOf(toolBindings);
    skillBindings = List.copyOf(skillBindings);
    subagentBindings = List.copyOf(subagentBindings);
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    requireOneToOneProviderTools(providerRequest, toolBindings);
    requireUniqueToolNames(toolBindings);
    requireUniqueSkillNames(skillBindings);
    requireUniqueSubagentNames(subagentBindings);
    requireConsistentRoutes(environment, toolBindings, skillBindings);
    requireCompactionRequestShape(
        providerRequest, toolBindings, skillBindings, subagentBindings, compaction);
  }

  private static void requireCompactionRequestShape(
      ProviderRequest providerRequest,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      List<SubagentBinding> subagentBindings,
      CompactionRequest compaction) {
    if (compaction == null) {
      return;
    }
    if (!toolBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry tool bindings");
    }
    if (!skillBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry skill bindings");
    }
    if (!subagentBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry subagent bindings");
    }
    if (!providerRequest.tools().isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry provider tools");
    }
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

  private static void requireUniqueSubagentNames(List<SubagentBinding> subagentBindings) {
    for (int i = 0; i < subagentBindings.size(); i++) {
      String name = subagentBindings.get(i).name();
      for (int j = i + 1; j < subagentBindings.size(); j++) {
        if (name.equals(subagentBindings.get(j).name())) {
          throw new IllegalArgumentException("subagent binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireConsistentRoutes(
      EnvironmentBinding environment,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings) {
    for (ToolBinding binding : toolBindings) {
      if (binding.type() == ToolType.ENVIRONMENT
          && !Objects.equals(environment, binding.environment())) {
        throw new IllegalArgumentException(
            "tool binding environment must match request environment");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironment() != null && !skill.sourceEnvironment().equals(environment)) {
        throw new IllegalArgumentException(
            "skill source environment must match request environment");
      }
    }
  }
}
