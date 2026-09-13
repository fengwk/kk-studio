package fun.fengwk.kkstudio.harness.mcp;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.util.List;
import java.util.Objects;

/**
 * 跨 Platform/Daemon 复用的 MCP 工具调用结果模型。
 *
 * <p>{@code detailsJson} 必须是合法 JSON object。
 *
 * @param error 工具调用是否以错误结束
 * @param contents 包含 text/image/resource 等在内的结果内容列表
 * @param detailsJson 结构化详情 JSON object 文本
 */
public record McpToolCallResult(boolean error, List<ResultContent> contents, String detailsJson) {

  public McpToolCallResult {
    Objects.requireNonNull(contents, "contents");
    contents = List.copyOf(contents);
    detailsJson = JsonValues.requireJsonObject(detailsJson, "detailsJson");
  }

  public static McpToolCallResult success(List<ResultContent> contents, String detailsJson) {
    return new McpToolCallResult(false, contents, detailsJson);
  }

  public static McpToolCallResult error(List<ResultContent> contents, String detailsJson) {
    return new McpToolCallResult(true, contents, detailsJson);
  }

  public static McpToolCallResult text(String text) {
    return new McpToolCallResult(false, List.of(new TextResultContent(text)), "{}");
  }

  public static McpToolCallResult errorText(String errorText) {
    return new McpToolCallResult(true, List.of(new TextResultContent(errorText)), "{}");
  }
}
