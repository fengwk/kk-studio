package fun.fengwk.kkstudio.platform.interaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 待处理分页的 keyset 游标：{@code <epochMilli>:<uuid>}，精确对应 storage 的 {@code (created_at, id)} 排序键。
 *
 * <p>游标只定位「已消费到的最后一行」，因此即使本该页被权限/归属过滤后为空，客户端仍可原样回传它继续翻页，不会漏项或死循环。
 */
final class InteractionCursor {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  private final Instant createdAt;
  private final UUID id;

  private InteractionCursor(Instant createdAt, UUID id) {
    this.createdAt = createdAt;
    this.id = id;
  }

  static InteractionCursor start() {
    return new InteractionCursor(Instant.EPOCH, ZERO_UUID);
  }

  /** 解析客户端回传的游标；空游标表示首屏，非法形状抛 {@link IllegalArgumentException}（翻译为 400）。 */
  static InteractionCursor parse(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return start();
    }
    int separator = cursor.indexOf(':');
    if (separator <= 0 || separator == cursor.length() - 1) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>");
    }
    long epochMilli;
    try {
      epochMilli = Long.parseLong(cursor.substring(0, separator));
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>", error);
    }
    if (epochMilli < 0) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>");
    }
    String idText = cursor.substring(separator + 1);
    UUID id;
    try {
      id = UUID.fromString(idText);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>", error);
    }
    if (!id.toString().equals(idText)) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>");
    }
    return new InteractionCursor(Instant.ofEpochMilli(epochMilli), id);
  }

  static String encode(Instant createdAt, UUID id) {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(id, "id");
    return createdAt.toEpochMilli() + ":" + id;
  }

  Instant createdAt() {
    return createdAt;
  }

  UUID id() {
    return id;
  }
}
