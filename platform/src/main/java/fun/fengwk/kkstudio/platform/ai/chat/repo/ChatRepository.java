package fun.fengwk.kkstudio.platform.ai.chat.repo;

import fun.fengwk.kkstudio.platform.ai.chat.service.model.Chat;

import java.util.List;
import java.util.UUID;

/** 持久化 Chat 集合仓库。 */
public interface ChatRepository {

  List<Chat> listNewestFirst();

  Chat getById(UUID id);

  /** 行级锁定读取（{@code for update}），用于深删除时固定 Chat 版本与关联枚举。 */
  Chat lockById(UUID id);

  /** KEY SHARE 锁定读取：归属授权路径用于阻止 Chat 删除但不串行化同 Chat 的并发接受。 */
  Chat lockForKeyShare(UUID id);

  boolean create(Chat chat);

  /** 基于 (id, expectedVersion) 的原子 CAS 更新。 */
  boolean updateById(Chat chat, long expectedVersion);

  /** 基于 (id, expectedVersion) 的原子 CAS 删除。 */
  boolean deleteById(UUID id, long expectedVersion);
}
