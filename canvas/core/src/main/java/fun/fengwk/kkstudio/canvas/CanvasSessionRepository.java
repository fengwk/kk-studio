package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.UUID;

/** Canvas 持有的 Harness Session 归属的持久化端口。 */
public interface CanvasSessionRepository {

  /**
   * 插入归属边（{@code created_at} 由数据库默认填充）；session 已存在（无论归属本 Canvas 还是 Chat）时抛 {@code
   * DuplicateKeyException}，由调用方（归属创建事务）负责互斥语义。
   */
  boolean insert(UUID sessionId, UUID canvasId);

  /**
   * 单归属互斥插入：单条 SQL 内先确认另一归属方（Chat）不持有该 Session，再插入 Canvas 归属边（guaranteed atomic）。返回受影响行数：1 插入成功；0
   * 表示该 Session 已归 Chat（拒绝）；本表已存在则在 DB 层以 {@code DuplicateKeyException} 冲突。preflight 在 Runtime 建
   * Thread 之后执行，无法再取 harness SESSION 锁（锁序不容回退），因此互斥由本语句与 {@code harness_session} 主键唯一性共同保证。
   */
  int insertIfNotOwnedByOther(UUID sessionId, UUID canvasId);

  /** 枚举某 Canvas 的全部 Session id（owner listing），按归属时间倒序。 */
  List<UUID> listSessionIds(UUID canvasId);

  /** 按 Session id 读取归属边；不存在返回 {@code null}。 */
  CanvasSession findBySessionId(UUID sessionId);

  /** 删除指定 Session 的归属边并返回删除行数（深删除时先于 Session 行删除）。 */
  int deleteBySessionId(UUID sessionId);
}
