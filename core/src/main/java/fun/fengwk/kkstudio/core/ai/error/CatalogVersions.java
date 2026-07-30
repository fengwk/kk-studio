package fun.fengwk.kkstudio.core.ai.error;

import java.util.regex.Pattern;

/**
 * Decimal-string representation of optimistic-lock versions on the AI catalog.
 *
 * <p>Versions are non-negative decimal strings ("0", "1", "2", ...) exposed at the HTTP / DTO
 * boundary. Negative, blank, non-decimal, or out-of-range values are rejected as validation
 * failures so CAS never sees an unparseable token.
 */
public final class CatalogVersions {

  private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)$");

  private CatalogVersions() {}

  /** Parses a decimal string version. Returns the parsed long on success. */
  public static long parse(String value, String field) {
    if (value == null) {
      throw new AiValidationException(
          field, field + " must not be null (expectedVersion is required)");
    }
    if (!value.equals(value.trim())) {
      throw new AiValidationException(
          field, field + " must be a canonical non-negative decimal string: " + value);
    }
    if (value.isEmpty()) {
      throw new AiValidationException(field, field + " must not be blank");
    }
    if (!DECIMAL.matcher(value).matches()) {
      throw new AiValidationException(
          field, field + " must be a non-negative decimal string: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new AiValidationException(field, field + " exceeds long range: " + value, error);
    }
  }

  /** Formats a non-negative internal version as its canonical decimal string. */
  public static String format(long value) {
    if (value < 0) {
      throw new IllegalStateException("catalog version must not be negative: " + value);
    }
    return Long.toString(value);
  }
}
