package fun.fengwk.kkstudio.core.ai.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;

import java.util.List;
import java.util.stream.Collectors;

/** 基于 PostgreSQL 的 Chat 集合仓库。 */
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
    target.setAgentName(chat.getAgentName());
    target.setEnvironmentName(chat.getEnvironmentName());
    target.setYoloEnabled(chat.isYoloEnabled());
    return target;
  }

  private Chat toModel(ChatDO row) {
    if (row == null) {
      return null;
    }
    Chat target = new Chat();
    target.setId(row.getId());
    target.setTitle(row.getTitle());
    target.setAgentName(row.getAgentName());
    target.setEnvironmentName(row.getEnvironmentName());
    target.setYoloEnabled(row.isYoloEnabled());
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
