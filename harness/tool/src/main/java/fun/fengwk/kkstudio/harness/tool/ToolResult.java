package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.util.List;
import java.util.Objects;

/** 一次工具调用的完整或部分结果。 */
public record ToolResult(
    String toolCallId, List<ResultContent> contents, boolean error, String detailsJson) {

  /** detailsJson 原始文本的 UTF-8 字节上限：1 MiB，保证任何树优先解析只面对有界输入。 */
  public static final int MAX_DETAILS_JSON_UTF8_BYTES = 1024 * 1024;

  /** contents 数组的元素上限，防止无界内容列表放大。 */
  public static final int MAX_CONTENT_ITEMS = 64;

  public ToolResult {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    Objects.requireNonNull(contents, "contents");
    if (contents.size() > MAX_CONTENT_ITEMS) {
      throw new IllegalArgumentException(
          "contents must not exceed " + MAX_CONTENT_ITEMS + " items");
    }
    contents = List.copyOf(contents);
    detailsJson = requireBoundedJsonObject(detailsJson);
  }

  private static String requireBoundedJsonObject(String detailsJson) {
    String normalized = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
    if (ResourceRef.utf8LengthUpTo(normalized, "detailsJson", MAX_DETAILS_JSON_UTF8_BYTES)
        > MAX_DETAILS_JSON_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "detailsJson must not exceed " + MAX_DETAILS_JSON_UTF8_BYTES + " UTF-8 bytes");
    }
    return JsonValues.requireJsonObject(normalized, "detailsJson");
  }

  /** 构造标准错误结果，确保错误能作为语义 ToolResult 传递。 */
  public static ToolResult error(String toolCallId, String message) {
    return new ToolResult(toolCallId, List.of(new TextResultContent(message)), true, "{}");
  }
}
