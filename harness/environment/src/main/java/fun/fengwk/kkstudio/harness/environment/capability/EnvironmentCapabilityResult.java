package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.Objects;

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

  /** 创建纯文本成功结果。 */
  public static EnvironmentCapabilityResult success(String callId, String text) {
    return text(callId, text);
  }

  /** 创建纯文本成功结果。 */
  public static EnvironmentCapabilityResult text(String callId, String text) {
    return new EnvironmentCapabilityResult(callId, List.of(new TextToolContent(text)), false, "{}");
  }

  /** 创建纯错误结果（统一附加 "Error: " 前缀）。 */
  public static EnvironmentCapabilityResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "capability execution failed" : message;
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextToolContent("Error: " + detail)), true, "{}");
  }

  /** 创建结构化 JSON 成功结果。 */
  public static EnvironmentCapabilityResult json(String callId, String json) {
    return new EnvironmentCapabilityResult(callId, List.of(new JsonToolContent(json)), false, "{}");
  }

  /** 创建包含文本预览与引用 Resource 的成功结果。 */
  public static EnvironmentCapabilityResult resource(
      String callId, String previewText, DaemonResourceRef resource) {
    Objects.requireNonNull(resource, "resource");
    return new EnvironmentCapabilityResult(
        callId,
        List.of(
            new TextToolContent(previewText),
            new ResourceToolContent(
                new ResourceRef(
                    resource.uri(),
                    resource.mediaType(),
                    resource.name(),
                    resource.size(),
                    resource.sha256()))),
        false,
        "{}");
  }

  /** 创建内联 Binary 成功结果。 */
  public static EnvironmentCapabilityResult binary(String callId, byte[] bytes, String mediaType) {
    return new EnvironmentCapabilityResult(
        callId, List.of(new BinaryToolContent(mediaType, bytes)), false, "{}");
  }
}
