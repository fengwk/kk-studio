package fun.fengwk.kkstudio.platform.chat.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatSessionDO;
import fun.fengwk.kkstudio.platform.chat.repo.impl.store.ChatSessionJdbcStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 基于 PostgreSQL 的 Chat↔Session 归属仓库。 */
@Repository
public class PostgresqlChatSessionRepository implements ChatSessionRepository {

  private final ChatSessionJdbcStore chatSessionJdbcStore;

  public PostgresqlChatSessionRepository(ChatSessionJdbcStore chatSessionJdbcStore) {
    this.chatSessionJdbcStore =
        Objects.requireNonNull(chatSessionJdbcStore, "chatSessionJdbcStore");
  }

  @Override
  public boolean insert(UUID sessionId, UUID chatId) {
    return chatSessionJdbcStore.insert(sessionId, chatId) == 1;
  }

  @Override
  public List<UUID> listSessionIds(UUID chatId) {
    return chatSessionJdbcStore.listSessionIds(chatId);
  }

  @Override
  public ChatSession findBySessionId(UUID sessionId) {
    ChatSessionDO row = chatSessionJdbcStore.findBySessionId(sessionId);
    return row == null ? null : new ChatSession(row.getSessionId(), row.getChatId());
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    return chatSessionJdbcStore.deleteBySessionId(sessionId);
  }
}
