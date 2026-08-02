package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * harness_execution_activation 的通用持久化端口。
 *
 * <p>Environment Tool 的 FIFO 推进由 {@link EnvironmentToolActivationQueue} 负责。本端口只管理激活记录本身、
 * 行锁、状态切换、到期扫描和唤醒时间查询。
 */
public interface ExecutionActivationStore {

  /**
   * 创建或提前安排一条 SCHEDULED 激活。新记录使用给定 Environment；已有 PARKED 记录不会被隐式唤醒， 已有 SCHEDULED 记录只会在 wakeAt 更早时更新
   * wakeAt。
   */
  int schedule(ExecutionTargetKind kind, long id, String environmentName, Instant wakeAt);

  /** 创建一条初始 PARKED 激活；已有记录保持不变。 */
  int park(ExecutionTargetKind kind, long id, String environmentName, Instant wakeAt);

  /** 在当前事务中锁定一条激活，不判断 wakeAt。 */
  Optional<ExecutionActivation> lock(ExecutionTargetKind kind, long id);

  /** 在当前事务中锁定一条已处于 SCHEDULED 且 wakeAt 不晚于当前时间的激活。 */
  Optional<ExecutionActivation> lockDue(ExecutionTargetKind kind, long id, Instant now);

  /** 修改已锁定激活的 wakeAt，保留 Environment 和 activationState。 */
  int rescheduleLocked(ExecutionTargetKind kind, long id, Instant wakeAt);

  /** 修改已锁定激活为 PARKED，保留 Environment 并写入 wakeAt。 */
  int parkLocked(ExecutionTargetKind kind, long id, Instant wakeAt);

  /** 修改已锁定激活为 SCHEDULED，保留 Environment 并写入 wakeAt。 */
  int activateLocked(ExecutionTargetKind kind, long id, Instant wakeAt);

  /** 删除已锁定激活。 */
  int deleteLocked(ExecutionTargetKind kind, long id);

  /** 无锁条件删除激活。 */
  int deleteIfExists(ExecutionTargetKind kind, long id);

  /** 扫描当前节点 Environment 快照下已到期的激活。 */
  List<ExecutionActivation> findEligibleDue(
      ExecutionActivationEnvironmentEligibility environmentEligibility, Instant now, int limit);

  /** 查询当前节点 Environment 快照下最早的 wakeAt。 */
  Optional<Instant> findNearestEligibleWakeAt(
      ExecutionActivationEnvironmentEligibility environmentEligibility);

  /** 查询全部激活记录，仅供检查和测试使用。 */
  List<ExecutionActivation> findAll();
}
