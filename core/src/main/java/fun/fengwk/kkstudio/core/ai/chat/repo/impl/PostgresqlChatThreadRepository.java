package fun.fengwk.kkstudio.core.ai.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper.ChatThreadMapper;

import java.util.List;

/** PostgreSQL adapter for the append-only Chat↔Thread association. */
@AllArgsConstructor
@Repository
public class PostgresqlChatThreadRepository implements ChatThreadRepository {

  private final ChatThreadMapper mapper;

  @Override
  public boolean associate(long chatId, long threadId) {
    return mapper.insert(chatId, threadId) == 1;
  }

  @Override
  public List<Long> listThreadIds(long chatId) {
    return mapper.listThreadIds(chatId);
  }
}
