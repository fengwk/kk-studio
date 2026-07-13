package fun.fengwk.kkstudio.harness.tool;

import java.util.List;
import java.util.Objects;

/** 一次工具调用的完整或部分结果。 */
public record ToolResult(String toolCallId, List<ToolContent> contents, boolean error) {

  public ToolResult {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
  }

  /** 构造标准错误结果，确保错误能作为语义 ToolResult 传递。 */
  public static ToolResult error(String toolCallId, String message) {
    return new ToolResult(toolCallId, List.of(new TextToolContent(message)), true);
  }
}
