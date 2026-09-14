package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.UUID;

/** Canvas 持有的 Harness Session 归属的持久化端口。 */
public interface CanvasSessionRepository {

  /**
   * 插入归属边（{@code created_at} 由数据库默认填充）；Session 已由任一产品 owner 持有时抛 {@code
   * DataIntegrityViolationException}。
   */
  boolean insert(UUID sessionId, UUID canvasId);

  /** 枚举某 Canvas 的全部 Session id（owner listing），按归属时间倒序。 */
  List<UUID> listSessionIds(UUID canvasId);

  /** 按 Session id 读取归属边；不存在返回 {@code null}。 */
  CanvasSession findBySessionId(UUID sessionId);

  /** 删除指定 Session 的归属边并返回删除行数（深删除时先于 Session 行删除）。 */
  int deleteBySessionId(UUID sessionId);
}
