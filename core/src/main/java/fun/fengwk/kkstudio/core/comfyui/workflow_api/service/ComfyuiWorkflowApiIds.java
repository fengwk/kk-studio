package fun.fengwk.kkstudio.core.comfyui.workflow_api.service;

import java.util.Objects;
import java.util.UUID;

/**
 * 在 web / DTO 边界暴露的 ComfyUI workflow 卡片 UUID id 的严格解析器。null、空白或非 canonical UUID 文本以 {@link
 * IllegalArgumentException} 拒绝，由全局 handler 映射为 HTTP 400。
 */
public final class ComfyuiWorkflowApiIds {

  private ComfyuiWorkflowApiIds() {}

  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(trimmed);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(trimmed)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  public static String format(UUID value) {
    return value == null ? null : value.toString();
  }
}
