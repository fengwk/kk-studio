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
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new AiValidationException(field, field + " must not be blank");
    }
    if (!DECIMAL.matcher(trimmed).matches()) {
      throw new AiValidationException(
          field, field + " must be a non-negative decimal string: " + value);
    }
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new AiValidationException(field, field + " exceeds long range: " + value, error);
    }
  }

  /** Formats a long version as a decimal string. Negative values map to "0". */
  public static String format(long value) {
    return Long.toString(Math.max(0L, value));
  }
}
