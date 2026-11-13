package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 Turn 解析完成后冻结的执行能力：工具名称引用、子代理 allowlist 与消费者媒体能力。
 *
 * <p>工具定义只存储在 SYSTEM 历史中；TurnStart 保留可执行工具名称与公共消费者能力。子代理名称唯一，媒体能力只含中立枚举，不携带 Provider 实现类型。
 */
public record TurnCapabilities(
    List<String> toolNames,
    List<SubagentBinding> subagentBindings,
    TurnMediaCapabilities mediaCapabilities) {

  public TurnCapabilities(List<String> toolNames, List<SubagentBinding> subagentBindings) {
    this(toolNames, subagentBindings, TurnMediaCapabilities.NONE);
  }

  public TurnCapabilities {
    toolNames = List.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    mediaCapabilities = Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    requireUniqueToolNames(toolNames);
    requireUniqueSubagentNames(subagentBindings);
  }

  private static void requireUniqueToolNames(List<String> toolNames) {
    Set<String> names = new HashSet<>();
    for (String name : toolNames) {
      Objects.requireNonNull(name, "toolNames[]");
      if (name.isBlank() || !name.equals(name.strip())) {
        throw new IllegalArgumentException("tool names must be non-blank and canonical");
      }
      if (!names.add(name)) {
        throw new IllegalArgumentException("tool names must not repeat: " + name);
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
