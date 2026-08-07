package fun.fengwk.kkstudio.core.comfyui.workflow_api.service;

import java.util.Objects;

/**
 * 在 web / DTO 边界暴露的 ComfyUI workflow 卡片 bigint id 的严格解析器。null、空白、非十进制或 非正值以 {@link
 * IllegalArgumentException} 拒绝，由全局 handler 映射为 HTTP 400。与 {@code HarnessIds} 保持一致，使持久的正十进制字符串 ID
 * 共享相同的边界语义。
 */
public final class ComfyuiWorkflowApiIds {

  private ComfyuiWorkflowApiIds() {}

  public static long parsePositive(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          field + " must be a positive long decimal: " + value, error);
    }
    if (parsed <= 0) {
      throw new IllegalArgumentException(field + " must be positive: " + value);
    }
    return parsed;
  }

  public static String format(long value) {
    return Long.toString(value);
  }
}
