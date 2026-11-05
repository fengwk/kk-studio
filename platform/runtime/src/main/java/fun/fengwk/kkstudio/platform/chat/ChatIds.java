package fun.fengwk.kkstudio.platform.chat;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.error.DomainException;

import java.util.Objects;
import java.util.UUID;

/**
 * 在 web / DTO 边界暴露的 Chat / Chat Session UUID id 的严格解析器。
 *
 * <p>只接受 canonical UUID 文本（{@link UUID#fromString} 与 {@code toString} 往返一致）。拒绝时抛 {@link
 * DomainException}，由全局 handler 映射为 HTTP 400。
 */
public final class ChatIds {

  private ChatIds() {}

  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("chat", error.getMessage());
    }
  }

  public static String format(UUID value) {
    return value == null ? null : value.toString();
  }
}
