package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Model / Tool / local / remote 共用的退出确认入口。
 *
 * <p>数据库任何读取或提交之前先把正向 proof 冻结到本机 write-ahead journal；数据库不可用时文件仍在，重启后可重放。确认与 Join 结算提交后才
 * 删除记录。快路径只锁本 Thread，Thread 为 RUNNABLE 且没有未完成 Join 时只推进 version；需要祖先结算时先结束只读探测事务，再以 {@link
 * ExecutionExitLedger#lockAncestorChain} 作为新事务第一把锁重新确认。
 *
 * <p>同一 execution 的确认在进程内按稳定 stripe 串行化，保证并发回调只产生一次 version 推进与一次 journal 删除。合法深删已提交时 Thread
 * 与事实一起消失，确认按 missing-safe 结束并删除 journal；Thread 仍存在但事实缺失是持久化不变量破坏，保留 journal 并失败。 journal
 * 无法写入时异常向调用方传播，调用方不得宣称 durable。
 */
@Slf4j
public final class ExecutionExitConfirmer {

  private static final int LOCK_STRIPES = 64;

  private final HarnessStore store;
  private final ExecutionExitJournal journal;
  private final Clock clock;
  private final Object[] executionLocks = new Object[LOCK_STRIPES];

  public ExecutionExitConfirmer(HarnessStore store, ExecutionExitJournal journal, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
    for (int i = 0; i < executionLocks.length; i++) {
      executionLocks[i] = new Object();
    }
  }

  /** 用真实退出证据确认；journal 写入失败或事实缺失/冲突时抛出，数据库确认失败保留 journal 后原样抛出。 */
  public Result confirm(ExecutionExitFact expected, ExecutionExitState state) {
    return locked(expected.executionId(), () -> confirmInternal(expected, state, null));
  }

  /** 重放 journal 中所有待确认记录；单条失败保留该记录并继续其它记录。 */
  public void replayPending() {
    for (ExecutionExitJournalRecord record : journal.loadAll()) {
      try {
        locked(
            record.executionId(),
            () ->
                confirmInternal(
                    record.expected(record.confirmedAt()),
                    record.targetState(),
                    record.confirmedAt()));
      } catch (RuntimeException failure) {
        log.warn(
            "cannot replay execution exit confirmation for {};"
                + " phase=EXIT_CONFIRMATION_REPLAY; exception={}",
            record.executionId(),
            failure.getClass().getSimpleName());
      }
    }
  }

  private <T> T locked(UUID executionId, Supplier<T> action) {
    synchronized (executionLocks[Math.floorMod(executionId.hashCode(), LOCK_STRIPES)]) {
      return action.get();
    }
  }

  private Result confirmInternal(
      ExecutionExitFact expected, ExecutionExitState state, Instant replayedAt) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    if (!state.confirmed()) {
      throw new IllegalArgumentException("confirmation requires real exit evidence");
    }
    Instant observedAt = replayedAt == null ? clock.instant() : replayedAt;
    if (observedAt.isBefore(expected.registeredAt())) {
      observedAt = expected.registeredAt();
    }
    // 正向 proof 必须先落盘；此时尚未读取数据库，数据库不可用也不会丢失 durable 重试依据。
    ExecutionExitJournalRecord canonical = journal.write(proof(expected, state, observedAt));
    Instant confirmedAt = canonical.confirmedAt();

    Attempt fast = store.transaction(tx -> attemptFast(tx, expected, state, confirmedAt));
    if (fast == Attempt.RETRY_SLOW) {
      fast = store.transaction(tx -> attemptSlow(tx, expected, state, confirmedAt));
    }
    return complete(expected.executionId(), fast);
  }

  private Result complete(UUID executionId, Attempt attempt) {
    return switch (attempt) {
      case CONFIRMED -> {
        // 只有确认事务成功提交后才删除 journal。
        deleteJournal(executionId);
        yield new Result(Outcome.CONFIRMED);
      }
      case THREAD_MISSING -> {
        deleteJournal(executionId);
        yield new Result(Outcome.THREAD_MISSING);
      }
      case FACT_MISSING -> throw new IllegalStateException(
          "thread still exists but execution " + executionId + " is missing");
      case CONFLICT -> throw new IllegalArgumentException(
          "execution " + executionId + " identity cannot be replaced");
      case RETRY_SLOW -> throw new IllegalStateException("slow confirmation path was not retried");
    };
  }

  private Attempt attemptFast(
      HarnessStore.Transaction tx,
      ExecutionExitFact expected,
      ExecutionExitState state,
      Instant confirmedAt) {
    ThreadState thread = tx.lockThread(expected.threadId()).orElse(null);
    if (thread == null) {
      return Attempt.THREAD_MISSING;
    }
    if (thread.executionControl() != ThreadExecutionControl.RUNNABLE
        || !tx.loadIncompleteJoins(expected.threadId()).isEmpty()) {
      return Attempt.RETRY_SLOW;
    }
    return confirmLocked(tx, expected, state, confirmedAt, true);
  }

  private Attempt attemptSlow(
      HarnessStore.Transaction tx,
      ExecutionExitFact expected,
      ExecutionExitState state,
      Instant confirmedAt) {
    ExecutionExitLedger.lockAncestorChain(tx, expected.threadId());
    if (tx.lockThread(expected.threadId()).isEmpty()) {
      return Attempt.THREAD_MISSING;
    }
    return confirmLocked(tx, expected, state, confirmedAt, false);
  }

  private Attempt confirmLocked(
      HarnessStore.Transaction tx,
      ExecutionExitFact expected,
      ExecutionExitState state,
      Instant confirmedAt,
      boolean fastPath) {
    ExecutionExitFact current = tx.findExecution(expected.executionId()).orElse(null);
    if (current == null) {
      return Attempt.FACT_MISSING;
    }
    if (!current.sameIdentity(expected)) {
      return Attempt.CONFLICT;
    }
    if (current.state().confirmed() && current.state() != state) {
      return Attempt.CONFLICT;
    }
    ExecutionExitLedger.Confirmation confirmation =
        ExecutionExitLedger.confirm(tx, current, state, confirmedAt)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "execution " + expected.executionId() + " remains unconfirmed"));
    if (fastPath) {
      ExecutionExitLedger.advanceFastPath(tx, expected.threadId(), confirmation);
    } else {
      ExecutionExitLedger.advanceAfterConfirmation(tx, expected.threadId(), confirmation);
    }
    return Attempt.CONFIRMED;
  }

  private static ExecutionExitJournalRecord proof(
      ExecutionExitFact expected, ExecutionExitState state, Instant observedAt) {
    return new ExecutionExitJournalRecord(
        expected.executionId(),
        expected.threadId(),
        expected.invocationId(),
        expected.attempt(),
        expected.ownerLeaseToken(),
        expected.kind(),
        expected.parentExecutionId(),
        state,
        observedAt);
  }

  private void deleteJournal(UUID executionId) {
    try {
      journal.delete(executionId);
    } catch (RuntimeException failure) {
      log.warn(
          "cannot delete execution exit journal entry for {};"
              + " phase=EXIT_CONFIRMATION_JOURNAL_DELETE; exception={}",
          executionId,
          failure.getClass().getSimpleName());
    }
  }

  /** 一次确认的对外结果。 */
  public record Result(Outcome outcome) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
    }
  }

  /** 确认结果分类；数据库异常不在这里分类，而是原样抛出并保留 journal。 */
  public enum Outcome {
    CONFIRMED,
    THREAD_MISSING
  }

  private enum Attempt {
    CONFIRMED,
    THREAD_MISSING,
    FACT_MISSING,
    CONFLICT,
    RETRY_SLOW
  }
}
