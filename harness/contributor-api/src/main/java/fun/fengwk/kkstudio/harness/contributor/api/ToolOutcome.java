package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * 工具执行完成时的终态结果，包含模型可见的 {@link ToolResult} 与附带的自定义状态追加条目 effects。
 *
 * <p>若 {@code result.error()} 为 true，则不允许附带任何 effects。
 */
public record ToolOutcome(ToolResult result, List<AppendCustomEntry> customEntries) {

  public ToolOutcome {
    result = Objects.requireNonNull(result, "result");
    customEntries = List.copyOf(Objects.requireNonNull(customEntries, "customEntries"));
    for (AppendCustomEntry entry : customEntries) {
      Objects.requireNonNull(entry, "customEntries[]");
    }
    if (result.error() && !customEntries.isEmpty()) {
      throw new IllegalArgumentException("an error ToolResult must not carry effects");
    }
  }

  /** 构造不带任何 effects 的 ToolOutcome。 */
  public static ToolOutcome withoutEffects(ToolResult result) {
    return new ToolOutcome(result, List.of());
  }
}
