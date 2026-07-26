package fun.fengwk.kkstudio.core.chat.repo;

import fun.fengwk.kkstudio.core.chat.service.model.Chat;

import java.util.List;

/** Persistent Chat collection repository. */
public interface ChatRepository {

  List<Chat> listNewestFirst();

  Chat getById(long id);

  boolean create(Chat chat);

  boolean updateById(Chat chat);

  boolean deleteById(long id);
}
