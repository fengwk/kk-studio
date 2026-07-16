package fun.fengwk.kkstudio.core.harness.session.support;

import java.util.Objects;

/**
 * Strict parser for the bigint ids exposed by the harness API. Rejects null, blank, non-decimal or
 * non-positive values with IllegalArgumentException, mapping to HTTP 400.
 */
public final class HarnessIds {

  private HarnessIds() {}

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
