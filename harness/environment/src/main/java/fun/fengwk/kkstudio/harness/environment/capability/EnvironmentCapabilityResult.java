package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.util.List;
import java.util.Objects;

/** Environment Capability 的部分或终止结果。 */
public record EnvironmentCapabilityResult(
    String callId, List<ResultContent> contents, boolean error, String detailsJson) {

  /** detailsJson 原始文本的 UTF-8 字节上限：1 MiB。 */
  public static final int MAX_DETAILS_JSON_UTF8_BYTES = 1024 * 1024;

  /** contents 数组的元素上限。 */
  public static final int MAX_CONTENT_ITEMS = 64;

  public EnvironmentCapabilityResult {
    if (callId == null || callId.isBlank()) {
      throw new IllegalArgumentException("callId must not be blank");
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

  /** 创建纯文本成功结果。 */
  public static EnvironmentCapabilityResult success(String callId, String text) {
    return text(callId, text);
  }

  /** 创建纯文本成功结果。 */
  public static EnvironmentCapabilityResult text(String callId, String text) {
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextResultContent(text)), false, "{}");
  }

  /** 创建纯错误结果（统一附加 "Error: " 前缀）。 */
  public static EnvironmentCapabilityResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "capability execution failed" : message;
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextResultContent("Error: " + detail)), true, "{}");
  }

  /**
   * 创建带稳定错误码的错误结果：文本仍可读，同时 details 携带机器可判定的 {@code code}。
   *
   * <p>details 形状固定为 {@code {"code":"<UPPER_SNAKE>"}}，不携带任何本地事实（路径、URL、正文）；{@code code} 必须满足受限语法。
   */
  public static EnvironmentCapabilityResult codedError(String callId, String code, String message) {
    String validated = EnvironmentCapabilityResultCodes.requireCode(code);
    String detail = message == null || message.isBlank() ? "capability execution failed" : message;
    String detailsJson = "{\"code\":\"" + validated + "\"}";
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextResultContent("Error: " + detail)), true, detailsJson);
  }

  /** 创建结构化 JSON 成功结果。 */
  public static EnvironmentCapabilityResult json(String callId, String json) {
    return new EnvironmentCapabilityResult(
        callId, List.of(new JsonResultContent(json)), false, "{}");
  }

  /** 创建包含文本预览与引用 Resource 的成功结果。 */
  public static EnvironmentCapabilityResult resource(
      String callId, String previewText, DaemonResourceRef resource) {
    Objects.requireNonNull(resource, "resource");
    return new EnvironmentCapabilityResult(
        callId,
        List.of(
            new TextResultContent(previewText),
            new ResourceResultContent(
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
        callId, List.of(new BinaryResultContent(mediaType, bytes)), false, "{}");
  }
}
