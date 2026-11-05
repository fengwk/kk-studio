package fun.fengwk.kkstudio.chat;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话列表的 keyset 游标，对应 {@code chat_session} 的 {@code (created_at desc, session_id desc)} 排序键。
 *
 * <p>编码为 {@code <epochMilli>:<uuid>}，与仓库的毫秒精度 {@code created_at} 精确对应。空游标表示首屏；非法形状抛 {@link
 * IllegalArgumentException}（翻译为 400）。
 */
record ChatSessionCursor(Instant createTime, UUID sessionId) {

  ChatSessionCursor {
    Objects.requireNonNull(createTime, "createTime");
    Objects.requireNonNull(sessionId, "sessionId");
  }

  static ChatSessionCursor of(ChatSessionRow row) {
    return new ChatSessionCursor(row.createTime(), row.sessionId());
  }

  static ChatSessionCursor parse(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
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
    try {
      return new ChatSessionCursor(
          Instant.ofEpochMilli(epochMilli), Uuids.parseCanonical(idText, "cursor"));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("cursor must be <epochMilli>:<uuid>", error);
    }
  }

  String encode() {
    return createTime.toEpochMilli() + ":" + sessionId;
  }
}
