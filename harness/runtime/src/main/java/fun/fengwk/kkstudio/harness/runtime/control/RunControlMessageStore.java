package fun.fengwk.kkstudio.harness.runtime.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 控制消息持久化最小 port：插入/查找、按 run 或 session 拉 PENDING、CAS 推进 status 与 run 维度的清空。 */
public interface RunControlMessageStore {

  /** 入库；返回受影响行数。 */
  int insert(RunControlMessage message);

  /** 按主键查找。 */
  Optional<RunControlMessage> find(long controlId);

  /**
   * 拉取指定 original run + kind 的 PENDING 行；按 id asc。
   * 调用方负责持有 Run/Session 锁，避免与 CAS 推进竞争；
   * 若 originalRunId 为 null（如 FOLLOW_UP 尚未挂到 run），查询结果为空。
   */
  List<RunControlMessage> listPendingByRun(long originalRunId, RunControlKind kind);

  /** 拉取指定 session 的所有 PENDING 行；按 id asc。 */
  List<RunControlMessage> listPendingBySession(long sessionId);

  /**
   * CAS：仅当行仍为 PENDING 时推进为 CONSUMED；返回 true 表示推进成功。
   * consumedRunId/consumedEntryId 决定后不可变；consumedAt 由实现根据 now 写入。
   */
  boolean markConsumed(long controlId, long consumedRunId, long consumedEntryId, Instant now);

  /**
   * CAS：仅当行仍为 PENDING 时推进为 PROMOTED；调用方传入新创建或即将创建的目标 run id
   * （FOLLOW_UP 直接 promotion 时即新 run 的 id）。
   */
  boolean markPromoted(long controlId, long consumedRunId, long consumedEntryId, Instant now);

  /** CAS：仅当行仍为 PENDING 时推进为 CLEARED；不伪造 consumedRunId/consumedEntryId。 */
  boolean markCleared(long controlId, Instant now);

  /** 批量清空指定 run 的所有 PENDING；返回受影响行数。 */
  int clearPendingByRun(long originalRunId, Instant now);
}
