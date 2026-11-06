package fun.fengwk.kkstudio.chat;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 对话列表的 keyset 游标，对应 {@code chat} 表的 {@code (updated_at desc, created_at desc, title asc, id asc)}
 * 排序键。
 *
 * <p>编码为 {@code v1:<updatedEpochMilli>:<createdEpochMilli>:<titleBase64Url>:<uuid>}。title 单独的
 * Base64 URL 编码使 任意文本（含分隔符）都能原样往返；null title 以 {@code ~} 表示（不是 Base64 URL 字符），并保持 PostgreSQL ASC 的
 * NULLS LAST 语义。游标只定位「已消费到的最后一行」，客户端原样回传即可继续翻页。
 */
record ChatCursor(Instant updatedAt, Instant createdAt, String title, UUID id) {

  private static final String VERSION = "v1";
  private static final String NULL_TITLE = "~";

  ChatCursor {
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(id, "id");
  }

  static ChatCursor of(Chat chat) {
    return new ChatCursor(chat.getUpdatedAt(), chat.getCreatedAt(), chat.getTitle(), chat.getId());
  }

  /** 解析客户端回传的游标；空游标表示首屏，非法形状抛 {@link IllegalArgumentException}（翻译为 400）。 */
  static ChatCursor parse(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    String[] parts = cursor.split(":", 5);
    if (parts.length != 5 || !VERSION.equals(parts[0])) {
      throw new IllegalArgumentException(
          "cursor must be <v1>:<updatedEpochMilli>:<createdEpochMilli>:<title>:<uuid>");
    }
    long updatedMilli = parseMillis(parts[1]);
    long createdMilli = parseMillis(parts[2]);
    String title = parseTitle(parts[3]);
    UUID id = parseId(parts[4]);
    return new ChatCursor(
        Instant.ofEpochMilli(updatedMilli), Instant.ofEpochMilli(createdMilli), title, id);
  }

  String encode() {
    String encodedTitle =
        title == null
            ? NULL_TITLE
            : Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(title.getBytes(StandardCharsets.UTF_8));
    return VERSION
        + ":"
        + updatedAt.toEpochMilli()
        + ":"
        + createdAt.toEpochMilli()
        + ":"
        + encodedTitle
        + ":"
        + id;
  }

  private static long parseMillis(String text) {
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "cursor timestamps must be non-negative epoch milliseconds", error);
    }
    if (value < 0) {
      throw new IllegalArgumentException(
          "cursor timestamps must be non-negative epoch milliseconds");
    }
    return value;
  }

  private static String parseTitle(String text) {
    if (NULL_TITLE.equals(text)) {
      return null;
    }
    if (text.isEmpty()) {
      throw new IllegalArgumentException("cursor title must not be empty");
    }
    try {
      return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("cursor title must be Base64 URL encoded", error);
    }
  }

  private static UUID parseId(String text) {
    try {
      return Uuids.parseCanonical(text, "cursor");
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("cursor must end with a canonical UUID", error);
    }
  }
}
