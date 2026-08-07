package fun.fengwk.kkstudio.core.ai.chat.repo;

import java.util.List;

/** Chat↔Thread 历史关联的幂等持久化边界。 */
public interface ChatThreadRepository {

  /** 新插入关联时返回 {@code true}，已存在时返回 {@code false}。 */
  boolean associate(long chatId, long threadId);

  /** 返回关联的 Thread id 列表，最新关联在前（不可变列表）。 */
  List<Long> listThreadIds(long chatId);
}
