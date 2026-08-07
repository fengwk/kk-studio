package fun.fengwk.kkstudio.core.ai.chat.repo;

import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;

import java.util.List;

/** 持久化 Chat 集合仓库。 */
public interface ChatRepository {

  List<Chat> listNewestFirst();

  Chat getById(long id);

  boolean create(Chat chat);

  /** 基于 (id, expectedVersion) 的原子 CAS 更新。 */
  boolean updateById(Chat chat, long expectedVersion);

  /** 基于 (id, expectedVersion) 的原子 CAS 删除。 */
  boolean deleteById(long id, long expectedVersion);
}
