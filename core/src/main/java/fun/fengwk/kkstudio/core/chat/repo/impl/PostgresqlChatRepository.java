package fun.fengwk.kkstudio.core.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;

import java.util.List;
import java.util.stream.Collectors;

/** PostgreSQL-backed Chat collection repository. */
@AllArgsConstructor
@Repository
public class PostgresqlChatRepository implements ChatRepository {

  private final ChatMapper chatMapper;

  @Override
  public List<Chat> listNewestFirst() {
    return chatMapper.listNewestFirst().stream().map(this::toModel).collect(Collectors.toList());
  }

  @Override
  public Chat getById(long id) {
    return toModel(chatMapper.getById(id));
  }

  @Override
  public boolean create(Chat chat) {
    return chatMapper.insert(toDO(chat)) == 1;
  }

  @Override
  public boolean updateById(Chat chat, long expectedVersion) {
    return chatMapper.updateById(toDO(chat), expectedVersion) == 1;
  }

  @Override
  public boolean deleteById(long id, long expectedVersion) {
    return chatMapper.deleteById(id, expectedVersion) == 1;
  }

  private ChatDO toDO(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDO target = new ChatDO();
    target.setId(chat.getId());
    target.setTitle(chat.getTitle());
    target.setDefaultAgentId(chat.getDefaultAgentId());
    return target;
  }

  private Chat toModel(ChatDO row) {
    if (row == null) {
      return null;
    }
    Chat target = new Chat();
    target.setId(row.getId());
    target.setTitle(row.getTitle());
    target.setDefaultAgentId(row.getDefaultAgentId());
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
