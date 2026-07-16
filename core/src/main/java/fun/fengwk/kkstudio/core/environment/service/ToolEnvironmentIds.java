package fun.fengwk.kkstudio.core.environment.service;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict parser for tool_environment bigint ids exposed at the web / DTO boundary and reused for
 * inline {@code environment:<id>/<tool>@<version>} references.
 *
 * <p>Accepts only a non-empty positive decimal text matching {@code ^[1-9][0-9]*$}; rejects {@code
 * +1}, {@code -1}, whitespace-only, zero, and {@link Long#MAX_VALUE}-overflowing inputs. Rejection
 * raises {@link IllegalArgumentException} so the global handler maps it to HTTP 400.
 */
public final class ToolEnvironmentIds {

  /** Unsigned positive decimal; 1..n with no sign, no leading zero. */
  private static final Pattern UNSIGNED_POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private ToolEnvironmentIds() {}

  public static long parsePositive(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!UNSIGNED_POSITIVE_DECIMAL.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(field + " must be an unsigned positive decimal: " + value);
    }
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds long range: " + value, error);
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
