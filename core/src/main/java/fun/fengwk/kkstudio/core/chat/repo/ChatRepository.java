package fun.fengwk.kkstudio.core.chat.repo;

import fun.fengwk.kkstudio.core.chat.service.model.Chat;

import java.util.List;

/** Persistent Chat collection repository. */
public interface ChatRepository {

  List<Chat> listNewestFirst();

  Chat getById(long id);

  boolean create(Chat chat);

  boolean updateById(Chat chat);

  boolean touch(long id);

  boolean deleteById(long id);

  boolean attachSession(long membershipId, long chatId, long sessionId);

  boolean isSessionAttached(long chatId, long sessionId);

  List<Long> listSessionIds(long chatId);

  boolean detachSession(long chatId, long sessionId);

  int deleteMembershipsByChatId(long chatId);
}
