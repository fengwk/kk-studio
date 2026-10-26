package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadTreeLocks;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.FinalAnswerEntry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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
            null,
            null));
  }

  /**
   * 用调用方冻结的完整身份确认退出。存储先读取真实行并比对身份，不符时拒绝且不改行；事实不存在时返回 empty。 重复的相同证据幂等；已确认后的不同证据是冲突，不静默保留。
   *
   * <p>{@link Confirmation#changed()} 只在本次事务把未确认事实推进到终态时为 true，重复 ACK 为 false，确认后的结算不得重复推进。
   */
  public static Optional<Confirmation> confirm(
      HarnessStore.Transaction tx,
      ExecutionExitFact expected,
      ExecutionExitState state,
      Instant confirmedAt) {
    Objects.requireNonNull(tx, "tx");
    boolean changed =
        tx.findExecution(expected.executionId())
            .map(stored -> !stored.state().confirmed())
            .orElse(false);
    return tx.confirmExecution(expected, state, confirmedAt)
        .map(fact -> new Confirmation(fact, changed));
  }

  /**
   * 用用户显式放弃证据解除收敛阻塞。放弃不是退出；重复放弃幂等，真实确认后的放弃是冲突。
   *
   * <p>{@link Confirmation#changed()} 只在本次事务把 UNCONFIRMED 推进到 ABANDONED 时为 true。
   */
  public static Optional<Confirmation> abandon(
      HarnessStore.Transaction tx, ExecutionExitFact expected, Instant abandonedAt) {
    Objects.requireNonNull(tx, "tx");
    boolean changed =
        tx.findExecution(expected.executionId())
            .map(stored -> stored.state() == ExecutionExitState.UNCONFIRMED)
            .orElse(false);
    return tx.abandonExecution(expected, abandonedAt).map(fact -> new Confirmation(fact, changed));
  }

  /** 一次确认的结果：冻结后的真实事实，以及本次是否真正改变了退出证据。 */
  public record Confirmation(ExecutionExitFact fact, boolean changed) {
    public Confirmation {
      Objects.requireNonNull(fact, "fact");
    }
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
   * 快路径推进：调用方已确认本 Thread 为 RUNNABLE 且没有未完成 Join，只需在确认真正改变事实时递增本 Thread version。
   *
   * <p>不进入 {@link #settleClosedTurn} 或任何祖先/父 Thread 结算，因此快路径不会因 Join 逻辑反向获取树锁。
   */
  public static void advanceFastPath(
      HarnessStore.Transaction tx, UUID threadId, Confirmation confirmation) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(confirmation, "confirmation");
    if (!confirmation.changed()) {
      return;
    }
    ThreadState thread = tx.findThread(threadId).orElse(null);
    if (thread == null) {
      return;
    }
    tx.updateThread(thread.touchVersion(tx.now()));
  }

  /**
   * 退出确认后的持久推进：确认真正改变事实时先递增本 Thread version；RUNNABLE Thread 若已物化普通终态则在同一事务内补结算 Join，仍有未完成 Join 时请求
   * THREAD Work，无 Join 则不安排任何推进；STOPPED Thread 递归结算已收敛祖先。重复 ACK 不触碰任何状态。
   */
  public static void advanceAfterConfirmation(
      HarnessStore.Transaction tx, UUID threadId, Confirmation confirmation) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(confirmation, "confirmation");
    if (!confirmation.changed()) {
      return;
    }
    ThreadState thread = tx.findThread(threadId).orElse(null);
    if (thread == null) {
      return;
    }
    ThreadState observed = thread.touchVersion(tx.now());
    tx.updateThread(observed);
    if (thread.executionControl() == ThreadExecutionControl.RUNNABLE) {
      if (settleClosedTurn(tx, observed)) {
        return;
      }
      if (hasActionableIncompleteJoin(tx, observed.id())) {
        tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
      }
      return;
    }
    settleStoppedAncestors(tx, threadId);
  }

  /** 只有源命令已应用、可在后续处理中结算的 Join 才需要 THREAD wake；未应用源命令不得抢先唤醒。 */
  private static boolean hasActionableIncompleteJoin(HarnessStore.Transaction tx, UUID threadId) {
    for (ThreadJoin join : tx.loadIncompleteJoins(threadId)) {
      ThreadCommand source =
          tx.findCommand(join.childThreadId(), join.sourceCommandSequence()).orElse(null);
      if (source != null && source.appliedEntryId() != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * 普通终态已经物化、只因未确认执行挡住 Join 时，在最后一次确认的同一事务内补结算。边界只取本 Thread 当前路径上属于自己的最近终态，允许其后追加
   * SETTINGS/NOTIFICATION 等尾部控制 Entry；不借用其它 Thread 的历史。
   */
  private static boolean settleClosedTurn(HarnessStore.Transaction tx, ThreadState thread) {
    if (tx.loadIncompleteJoins(thread.id()).isEmpty()) {
      return false;
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    if (path.openTurnStart().isPresent()) {
      return false;
    }
    Entry terminal = path.headIgnoringTrailingControlEntries();
    if (!(terminal.payload() instanceof TurnEndPayload end)) {
      return false;
    }
    int terminalIndex = path.entries().indexOf(terminal);
    Entry start = null;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(end.turnStartEntryId())) {
        start = entry;
      }
    }
    if (!(start != null
        && start.payload() instanceof TurnStartPayload turn
        && thread.id().equals(turn.ownerThreadId()))) {
      return false;
    }
    // continueModel 是尚未完成的机械续作，不能把这一轮 TURN_END 当成委派终态。
    if (end.continueModel()) {
      return false;
    }
    // 与普通终态物化共用同一最终回答判定：COMPLETED 且无续作义务时，只取紧邻 TURN_END 的 ASSISTANT 消息。
    Entry precedingEntry = terminalIndex > 0 ? path.entries().get(terminalIndex - 1) : null;
    UUID finalAnswerEntryId =
        FinalAnswerEntry.resolve(end.outcome(), end.continueModel(), precedingEntry);
    ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
        tx, thread, terminal.id(), finalAnswerEntryId, tx.now(), false);
    return true;
  }

  public static void settleStoppedAncestors(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    for (UUID ancestorId : tx.findAncestorChain(threadId)) {
      ThreadState thread = tx.findThread(ancestorId).orElse(null);
      if (thread == null || !thread.executionControl().isStopped()) {
        return;
      }
      // 停止边界的先后由当前 head 的历史路径确定；回执时间与 UUID 顺序都不能表达 Entry 历史先后。
      Entry terminal = tx.loadEntryPath(thread.headEntryId()).headIgnoringTrailingControlEntries();
      if (!(terminal.payload() instanceof TurnEndPayload end)
          || end.outcome() != TurnEndOutcome.STOPPED) {
        return;
      }
      StoppedThreadReceipt receipt =
          tx.findStopReceipt(thread.id(), end.closeRequestId()).orElse(null);
      if (receipt == null || !terminal.id().equals(receipt.stoppedTurnEndEntryId())) {
        return;
      }
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, thread, receipt.stoppedTurnEndEntryId(), null, tx.now(), true);
    }
  }
}
