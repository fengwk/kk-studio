package fun.fengwk.kkstudio.core.chat.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.core.chat.repo.impl.mapper.ChatSessionMapper;
import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatSessionDO;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;

import java.util.List;
import java.util.stream.Collectors;

/** MySQL / H2-backed Chat collection repository. */
@AllArgsConstructor
@Repository
public class MysqlChatRepository implements ChatRepository {

  private final ChatMapper chatMapper;
  private final ChatSessionMapper chatSessionMapper;

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
  public boolean touch(long id) {
    return chatMapper.touch(id) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return chatMapper.deleteById(id) == 1;
  }

  @Override
  public boolean attachSession(long membershipId, long chatId, long sessionId) {
    ChatSessionDO membership = new ChatSessionDO();
    membership.setId(membershipId);
    membership.setChatId(chatId);
    membership.setSessionId(sessionId);
    return chatSessionMapper.insert(membership) == 1;
  }

  @Override
  public boolean isSessionAttached(long chatId, long sessionId) {
    return chatSessionMapper.findByChatAndSession(chatId, sessionId) != null;
  }

  @Override
  public List<Long> listSessionIds(long chatId) {
    return chatSessionMapper.listSessionIdsByChatId(chatId);
  }

  @Override
  public boolean detachSession(long chatId, long sessionId) {
    return chatSessionMapper.deleteByChatAndSession(chatId, sessionId) == 1;
  }

  @Override
  public int deleteMembershipsByChatId(long chatId) {
    return chatSessionMapper.deleteByChatId(chatId);
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
