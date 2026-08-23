package fun.fengwk.kkstudio.platform.ai.chat.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.ai.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.ai.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.ai.chat.repo.impl.mapper.ChatSessionMapper;
import fun.fengwk.kkstudio.platform.ai.chat.repo.impl.model.ChatSessionDO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 基于 PostgreSQL 的 Chat↔Session 归属仓库。 */
@Repository
public class PostgresqlChatSessionRepository implements ChatSessionRepository {

  private final ChatSessionMapper mapper;

  public PostgresqlChatSessionRepository(ChatSessionMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public boolean insert(UUID sessionId, UUID chatId) {
    return mapper.insert(sessionId, chatId) == 1;
  }

  @Override
  public int insertIfNotOwnedByOther(UUID sessionId, UUID chatId) {
    return mapper.insertIfNotOwnedByOther(sessionId, chatId);
  }

  @Override
  public List<UUID> listSessionIds(UUID chatId) {
    return mapper.listSessionIds(chatId);
  }

  @Override
  public ChatSession findBySessionId(UUID sessionId) {
    ChatSessionDO row = mapper.findBySessionId(sessionId);
    return row == null ? null : new ChatSession(row.getSessionId(), row.getChatId());
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    return mapper.deleteBySessionId(sessionId);
  }
}
