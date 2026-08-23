package fun.fengwk.kkstudio.platform.ai.chat.service;

import fun.fengwk.kkstudio.platform.ai.error.AiValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * 在 web / DTO 边界暴露的 Chat / Chat Session UUID id 的严格解析器。
 *
 * <p>只接受 canonical UUID 文本（{@link UUID#fromString} 与 {@code toString} 往返一致）。拒绝时抛 {@link
 * AiValidationException}，由全局 handler 映射为 HTTP 400。
 */
public final class ChatIds {

  private ChatIds() {}

  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new AiValidationException("chat", field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new AiValidationException("chat", field + " must not be blank");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(trimmed);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("chat", field + " must be a canonical UUID: " + value);
    }
    if (!parsed.toString().equals(trimmed)) {
      throw new AiValidationException("chat", field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  public static String format(UUID value) {
    return value == null ? null : value.toString();
  }
}
