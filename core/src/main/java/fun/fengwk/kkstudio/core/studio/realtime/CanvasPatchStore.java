package fun.fengwk.kkstudio.core.studio.realtime;

import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas graph patch 的 bounded best-effort 缓存端口。
 *
 * <p>PostgreSQL 行是事实源，本端口只是 {@code afterCommit} 后写入的投影缓存：/changes 用它回放连续 patches， 任何缺失/gap/损坏都回退权威
 * snapshot。
 */
public interface CanvasPatchStore {

  /** 追加一个 patch（调用方负责在事务提交后调用；Redis 失败由调用方隔离）。 */
  void append(UUID canvasId, CanvasPatch patch);

  /** 按版本定位 patch（精确回放幂等响应用；缓存缺失返回 empty）。 */
  Optional<CanvasPatch> findByVersion(UUID canvasId, long version);

  /** 读取该 Canvas 的全部缓存 patch（从旧到新）；Redis 失败向上传播。 */
  List<CanvasPatch> readAll(UUID canvasId);
}
