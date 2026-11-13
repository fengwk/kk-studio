package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 Turn 解析完成后冻结的子代理 allowlist 与消费者媒体能力。
 *
 * <p>工具定义只存储在 SYSTEM 历史中；TurnStart 仅保留子代理名称唯一性约束与公共消费者能力。媒体能力只含中立枚举，不携带 Provider 实现类型。
 */
public record TurnCapabilities(
    List<SubagentBinding> subagentBindings, TurnMediaCapabilities mediaCapabilities) {

  public TurnCapabilities(List<SubagentBinding> subagentBindings) {
    this(subagentBindings, TurnMediaCapabilities.NONE);
  }

  public TurnCapabilities {
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    mediaCapabilities = Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    requireUniqueSubagentNames(subagentBindings);
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
