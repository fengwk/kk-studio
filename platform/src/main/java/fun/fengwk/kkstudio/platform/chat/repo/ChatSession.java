package fun.fengwk.kkstudio.platform.chat.repo;

import java.util.Objects;
import java.util.UUID;

/**
 * Chat 与 Harness Session 的一对一归属边。
 *
 * <p>{@code chat_session.session_id} 是主键，因此一个 Session 至多属于一个 Chat。
 */
public record ChatSession(UUID sessionId, UUID chatId) {

  public ChatSession {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(chatId, "chatId");
  }
}
