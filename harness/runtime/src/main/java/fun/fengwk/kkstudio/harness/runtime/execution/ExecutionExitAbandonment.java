package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户显式放弃一次执行代次退出确认的事务主体。
 *
 * <p>锁序为执行树 → Thread；放弃是独立事实，不冒充物理退出，也不取消任何执行。状态判断顺序固定为：目标 Thread 存在 → 执行事实存在 → 事实属于该 Thread → 已放弃
 * / 已确认即 replay → Thread 必须 STOPPED → expectedVersion 匹配 → 落账并推进祖先结算。
 */
public final class ExecutionExitAbandonment {

  private final HarnessStore store;

  public ExecutionExitAbandonment(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** 执行一次放弃；成功与 replay 都不改变调用方随后读取快照的方式。 */
  public void abandon(UUID threadId, UUID executionId, long expectedVersion) {
    store.transaction(
        tx -> {
          ExecutionExitLedger.lockAncestorChain(tx, threadId);
          ThreadState thread = tx.threads().lock(threadId).orElse(null);
          if (thread == null) {
            throw DomainException.notFound("thread", "thread " + threadId + " does not exist");
          }
          ExecutionExitFact stored = tx.exits().find(executionId).orElse(null);
          if (stored == null) {
            throw DomainException.notFound(
                "execution", "execution " + executionId + " does not exist");
          }
          if (!stored.threadId().equals(threadId)) {
            throw conflict(
                HarnessConflictReason.EXIT_ABANDON_NOT_APPLICABLE,
                "execution " + executionId + " does not belong to thread " + threadId);
          }
          if (stored.state() == ExecutionExitState.ABANDONED || stored.state().confirmed()) {
            return null;
          }
          if (!thread.executionControl().isStopped()) {
            throw conflict(
                HarnessConflictReason.EXIT_ABANDON_NOT_APPLICABLE,
                "thread " + threadId + " must be STOPPED before an exit can be abandoned");
          }
          if (thread.version() != expectedVersion) {
            throw conflict(
                HarnessConflictReason.STALE_VERSION,
                "thread "
                    + threadId
                    + " version is "
                    + thread.version()
                    + ", expected "
                    + expectedVersion);
          }
          ExecutionExitLedger.Confirmation confirmation =
              ExecutionExitLedger.abandon(tx, stored, tx.now())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "execution " + executionId + " remains unconfirmed"));
          ExecutionExitLedger.advanceAfterConfirmation(tx, threadId, confirmation);
          return null;
        });
  }

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }
}
