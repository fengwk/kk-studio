package fun.fengwk.kkstudio.core.ai.chat.repo;

import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;

import java.util.List;

/** Persistent Chat collection repository. */
public interface ChatRepository {

  List<Chat> listNewestFirst();

  Chat getById(long id);

  boolean create(Chat chat);

  /** Atomic CAS update on (id, expectedVersion). */
  boolean updateById(Chat chat, long expectedVersion);

  /** Atomic CAS delete on (id, expectedVersion). */
  boolean deleteById(long id, long expectedVersion);
}
