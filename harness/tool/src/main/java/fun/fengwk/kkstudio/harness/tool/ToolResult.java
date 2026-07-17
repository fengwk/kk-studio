package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

import java.util.List;
import java.util.Objects;

/**
 * 一次工具调用的完整或部分结果。
 *
 * <p>terminate 是 Runtime 的内存执行提示，不得作为 Session 语义消息或 Daemon 终态协议的替代。
 */
public record ToolResult(
    String toolCallId,
    List<ToolContent> contents,
    boolean error,
    String detailsJson,
    boolean terminate) {

  public ToolResult {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    detailsJson = ToolArgumentsValidator.requireJsonObject(detailsJson);
  }

  /** 构造标准错误结果，确保错误能作为语义 ToolResult 传递。 */
  public static ToolResult error(String toolCallId, String message) {
    return new ToolResult(toolCallId, List.of(new TextToolContent(message)), true, "{}", false);
  }
}
