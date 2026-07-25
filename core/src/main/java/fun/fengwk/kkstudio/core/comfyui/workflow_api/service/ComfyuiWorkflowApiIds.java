package fun.fengwk.kkstudio.core.comfyui.workflow_api.service;

import java.util.Objects;

/**
 * Strict parser for ComfyUI workflow card bigint ids exposed at the web / DTO boundary. Rejects
 * null, blank, non-decimal or non-positive values with {@link IllegalArgumentException}, which the
 * global handler maps to HTTP 400. Mirrors {@code HarnessIds} so durable positive decimal string
 * IDs share the same boundary semantics.
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
