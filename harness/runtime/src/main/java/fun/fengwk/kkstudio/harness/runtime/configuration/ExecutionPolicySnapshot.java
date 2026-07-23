package fun.fengwk.kkstudio.harness.runtime.configuration;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 冻结的执行策略快照：turn / depth / direct subagent 上限与 yolo 标志。
 *
 * <p>数值字段均为正整数；{@code maxTotalSubagents} 可空但当 present 时必须为正；{@code allowedSubagents} 元素 非空白且按字典序
 * canonical 唯一。
 */
public record ExecutionPolicySnapshot(
    int maxTurns,
    int maxDepth,
    int maxDirectSubagents,
    Integer maxTotalSubagents,
    List<String> allowedSubagents,
    boolean yoloEnabled) {

  public ExecutionPolicySnapshot {
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    if (maxDepth <= 0) {
      throw new IllegalArgumentException("maxDepth must be positive");
    }
    if (maxDirectSubagents <= 0) {
      throw new IllegalArgumentException("maxDirectSubagents must be positive");
    }
    if (maxTotalSubagents != null && maxTotalSubagents <= 0) {
      throw new IllegalArgumentException("maxTotalSubagents must be positive when present");
    }
    Objects.requireNonNull(allowedSubagents, "allowedSubagents");
    // canonical 化：非空、唯一、字典序；同时拒绝空白 / 重复。
    Set<String> unique = new TreeSet<>();
    for (String name : allowedSubagents) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("allowedSubagents must contain non-blank entries");
      }
      if (!unique.add(name)) {
        throw new IllegalArgumentException("allowedSubagents contains duplicate entry: " + name);
      }
    }
    allowedSubagents = List.copyOf(unique);
  }
}
