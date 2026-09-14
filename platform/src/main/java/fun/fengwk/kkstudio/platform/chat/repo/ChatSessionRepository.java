package fun.fengwk.kkstudio.platform.chat.repo;

import java.util.List;
import java.util.UUID;

/** Chat 持有的 Harness Session 归属的持久化端口。 */
public interface ChatSessionRepository {

  /**
   * 插入归属边（{@code created_at} 由数据库默认填充）；Session 已由任一产品 owner 持有时抛 {@code
   * DataIntegrityViolationException}。
   */
  boolean insert(UUID sessionId, UUID chatId);

  /** 枚举某 Chat 的全部 Session id（owner listing），按归属时间倒序。 */
  List<UUID> listSessionIds(UUID chatId);

  /** 按 Session id 读取归属边；不存在返回 {@code null}。 */
  ChatSession findBySessionId(UUID sessionId);

  /** 删除指定 Session 的归属边并返回删除行数（深删除时先于 Session 行删除）。 */
  int deleteBySessionId(UUID sessionId);
}
