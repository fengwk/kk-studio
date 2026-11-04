package fun.fengwk.kkstudio.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code chat_session} 的一行归属边：Session 身份与建立时间（列表 keyset 排序键）。 */
record ChatSessionRow(UUID sessionId, Instant createTime) {

  ChatSessionRow {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(createTime, "createTime");
  }
}
