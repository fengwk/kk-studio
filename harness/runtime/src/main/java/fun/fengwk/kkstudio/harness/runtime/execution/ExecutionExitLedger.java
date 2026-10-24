package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadTreeLocks;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 执行代次的登记、确认与确认后的祖先结算。
 *
 * <p>{@link #register} 与 {@link #confirm} 不获取树锁或新的事实行锁，调用方必须已经持有 Thread 锁。 {@link
 * #lockAncestorChain} 是确认后结算的独立入口，必须作为该事务的第一把锁。确认不要求 Work 仍存在，因此 Stop 删除 Work 后迟到的真实 ACK 仍可落账。
 */
public final class ExecutionExitLedger {

  private ExecutionExitLedger() {}

  /** 登记一次尚未退出的本地或远端执行。重复登记相同身份是幂等的。 */
  public static ExecutionExitFact register(
      HarnessStore.Transaction tx,
      UUID executionId,
      UUID threadId,
      UUID invocationId,
      int attempt,
      String ownerLeaseToken,
      ExecutionExitKind kind,
      UUID parentExecutionId) {
    Objects.requireNonNull(tx, "tx");
    Instant now = tx.now();
    return tx.registerExecution(
        new ExecutionExitFact(
            executionId,
            threadId,
            invocationId,
            attempt,
            ownerLeaseToken,
            kind,
            parentExecutionId,
            ExecutionExitState.UNCONFIRMED,
            now,
            null));
  }

  /**
   * 按已登记事实确认退出。先读出真实行，身份不符时存储层拒绝且不改行；事实不存在时返回 empty，调用方必须保留未确认并报告。 确认使用登记时冻结的身份，不使用调用方当前持有的 lease。
   */
  public static Optional<ExecutionExitFact> confirm(
      HarnessStore.Transaction tx, UUID executionId, ExecutionExitState state) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(executionId, "executionId");
    ExecutionExitFact stored = tx.findExecution(executionId).orElse(null);
    if (stored == null) {
      return Optional.empty();
    }
    return tx.confirmExecution(stored, state);
  }

  /** 当前 Thread 是否仍有未确认退出的具体执行。查询不依赖 Invocation 行是否还在。 */
  public static boolean hasUnconfirmed(HarnessStore.Transaction tx, UUID threadId) {
    return !Objects.requireNonNull(tx, "tx").loadUnconfirmedExecutions(threadId).isEmpty();
  }

  /** 在确认事务的第一个操作中锁定执行树、祖先 Session 与 Thread。确认失败或 Thread 未停止时，这些锁不产生额外写入。 */
  public static void lockAncestorChain(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    ThreadTreeLocks.lockForThread(tx, threadId);
    List<UUID> chain = tx.findAncestorChain(threadId);
    List<ThreadState> snapshots = new ArrayList<>();
    Set<UUID> sessionIds = new LinkedHashSet<>();
    for (UUID ancestorId : chain) {
      ThreadState ancestor = tx.findThread(ancestorId).orElse(null);
      if (ancestor == null) {
        break;
      }
      snapshots.add(ancestor);
      sessionIds.add(ancestor.sessionId());
    }
    for (UUID sessionId : sessionIds.stream().sorted(UuidOrder.COMPARATOR).toList()) {
      tx.lockSessionForKeyShare(sessionId);
    }
    snapshots.stream()
        .sorted((left, right) -> UuidOrder.COMPARATOR.compare(left.id(), right.id()))
        .forEach(snapshot -> tx.lockThread(snapshot.id()));
  }

  /**
   * 退出确认后的持久推进：RUNNABLE Thread 递增 version 并请求 THREAD Work，使原先被未退出事实挡住的普通 Join 能在下一次处理中结算；STOPPED
   * Thread 则在本事务内递归结算已收敛祖先。
   */
  public static void advanceAfterConfirmation(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    ThreadState thread = tx.findThread(threadId).orElse(null);
    if (thread == null) {
      return;
    }
    if (thread.executionControl() == ThreadExecutionControl.RUNNABLE) {
      tx.updateThread(thread.touchVersion(tx.now()));
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
      return;
    }
    settleStoppedAncestors(tx, threadId);
  }

  public static void settleStoppedAncestors(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    for (UUID ancestorId : tx.findAncestorChain(threadId)) {
      ThreadState thread = tx.findThread(ancestorId).orElse(null);
      if (thread == null || !thread.executionControl().isStopped()) {
        return;
      }
      StoppedThreadReceipt receipt =
          tx.loadStopReceiptsByThread(thread.id()).stream()
              .reduce((first, second) -> second)
              .orElse(null);
      if (receipt == null || receipt.stoppedTurnEndEntryId() == null) {
        return;
      }
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, thread, receipt.stoppedTurnEndEntryId(), null, tx.now(), true);
    }
  }
}
