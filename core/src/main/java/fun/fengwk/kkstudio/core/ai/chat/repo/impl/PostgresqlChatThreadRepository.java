package fun.fengwk.kkstudio.core.ai.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper.ChatThreadMapper;

import java.util.List;
import java.util.UUID;

/** 只追加 Chat↔Thread 关联的 PostgreSQL 适配器。 */
@AllArgsConstructor
@Repository
public class PostgresqlChatThreadRepository implements ChatThreadRepository {

  private final ChatThreadMapper mapper;

  @Override
  public boolean associate(UUID chatId, UUID threadId) {
    return mapper.insert(chatId, threadId) == 1;
  }

  @Override
  public List<UUID> listThreadIds(UUID chatId) {
    return mapper.listThreadIds(chatId);
  }
}
