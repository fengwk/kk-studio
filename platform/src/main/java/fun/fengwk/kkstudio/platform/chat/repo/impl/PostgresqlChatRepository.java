package fun.fengwk.kkstudio.platform.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;

import java.util.List;
import java.util.UUID;
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
  public Chat getById(UUID id) {
    return toModel(chatMapper.getById(id));
  }

  @Override
  public Chat lockById(UUID id) {
    return toModel(chatMapper.lockById(id));
  }

  @Override
  public Chat lockForKeyShare(UUID id) {
    return toModel(chatMapper.lockForKeyShare(id));
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
  public boolean deleteById(UUID id, long expectedVersion) {
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
    if (chat.getEnvironment() == null) {
      target.setEnvironmentName(null);
      target.setWorkspacePath(null);
    } else {
      target.setEnvironmentName(chat.getEnvironment().environmentName().value());
      target.setWorkspacePath(chat.getEnvironment().workspacePath());
    }
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
    if (row.getEnvironmentName() == null && row.getWorkspacePath() == null) {
      target.setEnvironment(null);
    } else {
      target.setEnvironment(
          new EnvironmentBinding(
              new EnvironmentName(row.getEnvironmentName()), row.getWorkspacePath()));
    }
    target.setYoloEnabled(row.isYoloEnabled());
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
