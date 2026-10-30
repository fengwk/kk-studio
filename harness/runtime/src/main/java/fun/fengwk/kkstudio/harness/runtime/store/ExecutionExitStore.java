package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行退出账本聚合的事务内视图。
 *
 * <p>登记身份不可更换；确认与放弃都只做单向推进，且不获取新的行锁。
 */
public interface ExecutionExitStore {

  /** 登记一次执行代次；相同 executionId 必须携带完全相同的身份。 */
  ExecutionExitFact register(ExecutionExitFact fact);

  /** 把已登记代次单向推进到真实退出证据。 */
  Optional<ExecutionExitFact> confirm(
      ExecutionExitFact expected, ExecutionExitState state, Instant confirmedAt);

  /** 用用户显式放弃解除收敛阻塞；不是物理退出。 */
  Optional<ExecutionExitFact> abandon(ExecutionExitFact expected, Instant abandonedAt);

  /** 读取 Thread 上尚未确认退出的执行代次。 */
  List<ExecutionExitFact> loadUnconfirmed(UUID threadId);

  /** 按 executionId 读取执行代次。 */
  Optional<ExecutionExitFact> find(UUID executionId);
}
