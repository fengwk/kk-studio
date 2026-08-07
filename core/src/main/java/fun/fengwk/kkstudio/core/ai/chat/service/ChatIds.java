package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.core.ai.error.AiValidationException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 在 web / DTO 边界暴露的 Chat / ChatSession bigint id 的严格解析器。
 *
 * <p>只接受匹配 {@code ^[1-9][0-9]*$} 的非空正十进制文本。拒绝时抛 {@link AiValidationException}， 由全局 handler 映射为 HTTP
 * 400。
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
