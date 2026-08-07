package fun.fengwk.kkstudio.core.ai.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper.ChatThreadMapper;

import java.util.List;

/** 只追加 Chat↔Thread 关联的 PostgreSQL 适配器。 */
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
