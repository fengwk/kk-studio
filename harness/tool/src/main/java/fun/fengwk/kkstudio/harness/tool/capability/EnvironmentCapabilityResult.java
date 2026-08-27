package fun.fengwk.kkstudio.harness.tool.capability;

import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;

/**
 * Environment Capability 的部分或终止结果。
 *
 * <p>结果边界和 details JSON 校验统一复用 {@link ToolResult}，避免 Capability 与模型 Tool 产生两套限制。
 */
public record EnvironmentCapabilityResult(
    String callId, List<ToolContent> contents, boolean error, String detailsJson) {

  /** detailsJson 原始文本的 UTF-8 字节上限，与 ToolResult 使用同一约束来源。 */
  public static final int MAX_DETAILS_JSON_UTF8_BYTES = ToolResult.MAX_DETAILS_JSON_UTF8_BYTES;

  /** contents 数组的元素上限，与 ToolResult 使用同一约束来源。 */
  public static final int MAX_CONTENT_ITEMS = ToolResult.MAX_CONTENT_ITEMS;

  public EnvironmentCapabilityResult {
    ToolResult validated = new ToolResult(callId, contents, error, detailsJson);
    callId = validated.toolCallId();
    contents = validated.contents();
    detailsJson = validated.detailsJson();
  }
}
