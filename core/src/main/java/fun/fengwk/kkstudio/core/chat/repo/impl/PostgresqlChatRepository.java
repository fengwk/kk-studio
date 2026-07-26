package fun.fengwk.kkstudio.core.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
  public boolean updateById(Chat chat) {
    return chatMapper.updateById(toDO(chat)) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return chatMapper.deleteById(id) == 1;
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
    target.setCreateTime(toLocalDateTime(row.getCreateTime()));
    target.setUpdateTime(toLocalDateTime(row.getUpdateTime()));
    return target;
  }

  private static LocalDateTime toLocalDateTime(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
