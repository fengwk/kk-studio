package fun.fengwk.kkstudio.core.chat.service;

import fun.fengwk.kkstudio.core.ai.error.AiValidationException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict parser for Chat / ChatSession bigint ids exposed at the web / DTO boundary.
 *
 * <p>Accepts only a non-empty positive decimal text matching {@code ^[1-9][0-9]*$}. Rejection
 * raises {@link AiValidationException} so the global handler maps it to HTTP 400.
 */
public final class ChatIds {

  private static final Pattern UNSIGNED_POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private ChatIds() {}

  public static long parsePositive(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new AiValidationException("chat", field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new AiValidationException("chat", field + " must not be blank");
    }
    if (!UNSIGNED_POSITIVE_DECIMAL.matcher(trimmed).matches()) {
      throw new AiValidationException(
          "chat", field + " must be an unsigned positive decimal: " + value);
    }
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new AiValidationException("chat", field + " exceeds long range: " + value);
    }
    if (parsed <= 0) {
      throw new AiValidationException("chat", field + " must be positive: " + value);
    }
    return parsed;
  }

  public static String format(long value) {
    return Long.toString(value);
  }
}
