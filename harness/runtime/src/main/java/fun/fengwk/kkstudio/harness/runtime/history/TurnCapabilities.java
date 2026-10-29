package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 Turn 解析完成后冻结的执行能力：工具 bindings、子代理 allowlist 与消费者媒体能力。
 *
 * <p>这是工具声明、审批、执行、{@code task} 父权限与媒体读取的公共权限来源。{@code ModelRequestSpec} 中的工具列表可以继续作为 Builtin
 * 的请求投影存在，但不得再作为工具授权事实。尚未解析（pending / rejected）的 TurnStart 允许没有 capabilities； 任何可执行 ToolInvocation
 * 都必须能引用其 TurnStart 中冻结的 binding。
 *
 * <p>工具 bindings 必须名称唯一并共享最多一个 Environment；子代理名称唯一。媒体能力只含中立枚举，不携带 Provider 实现类型。
 */
public record TurnCapabilities(
    List<ToolBinding> toolBindings,
    List<SubagentBinding> subagentBindings,
    TurnMediaCapabilities mediaCapabilities) {

  public TurnCapabilities(List<ToolBinding> toolBindings, List<SubagentBinding> subagentBindings) {
    this(toolBindings, subagentBindings, TurnMediaCapabilities.NONE);
  }

  public TurnCapabilities {
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    mediaCapabilities = Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    requireUniqueToolBindings(toolBindings);
    requireUniqueSubagentNames(subagentBindings);
    environment(toolBindings);
  }

  /** 冻结工具共享的唯一 Environment 身份；没有环境绑定工具时返回 null。 */
  public EnvironmentId environmentId() {
    return environment(toolBindings);
  }

  private static EnvironmentId environment(List<ToolBinding> toolBindings) {
    EnvironmentId environmentId = null;
    boolean seen = false;
    for (ToolBinding binding : toolBindings) {
      if (binding.environmentId() == null) {
        continue;
      }
      if (!seen) {
        environmentId = binding.environmentId();
        seen = true;
      } else if (!environmentId.equals(binding.environmentId())) {
        throw new IllegalArgumentException(
            "environment-bound tools in one turn must share one environment");
      }
    }
    return environmentId;
  }

  private static void requireUniqueToolBindings(List<ToolBinding> toolBindings) {
    Set<String> names = new HashSet<>();
    for (ToolBinding binding : toolBindings) {
      Objects.requireNonNull(binding, "toolBindings[]");
      if (!names.add(binding.descriptor().name())) {
        throw new IllegalArgumentException(
            "tool binding names must not repeat: " + binding.descriptor().name());
      }
    }
  }

  private static void requireUniqueSubagentNames(List<SubagentBinding> subagentBindings) {
    Set<String> names = new HashSet<>();
    for (SubagentBinding binding : subagentBindings) {
      Objects.requireNonNull(binding, "subagentBindings[]");
      if (!names.add(binding.name())) {
        throw new IllegalArgumentException(
            "subagent binding names must not repeat: " + binding.name());
      }
    }
  }
}
