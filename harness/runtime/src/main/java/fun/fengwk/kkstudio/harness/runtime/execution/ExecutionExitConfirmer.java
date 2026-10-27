package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Model / Tool / local / remote 共用的退出确认入口。
 *
 * <p>数据库任何读取或提交之前先把正向 proof 冻结到本机 write-ahead journal；数据库不可用时文件仍在，重启后可重放。确认与 Join 结算提交后才
 * 删除记录。快路径只锁本 Thread，Thread 为 RUNNABLE 且没有未完成 Join 时只推进 version；需要祖先结算时先结束只读探测事务，再以 {@link
 * ExecutionExitLedger#lockAncestorChain} 作为新事务第一把锁重新确认。
 *
 * <p>同一 execution 的确认在进程内按稳定 stripe 串行化，事实与 version 推进幂等，并始终复用待确认记录的第一次 canonical proof
 * 的身份、目标和确认时刻。首次 journal 写入失败时 proof 只保留在本进程内等待重试，不能宣称跨进程 durable。事实缺失或 canonical proof 与
 * journal/数据库的真实 identity/target 冲突只暂停该 execution 的自动重放；被拒绝的新输入不改变原 proof。其它数据库或文件失败按 {@link
 * #BACKOFF_SECONDS} 退避，成功后重置。
 */
@Slf4j
public final class ExecutionExitConfirmer {

  private static final int LOCK_STRIPES = 64;
  private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
  private static final String PHASE_CONFIRM = "EXIT_CONFIRMATION_CONFIRM";
  private static final String PHASE_REPLAY = "EXIT_CONFIRMATION_REPLAY";
  private static final String PHASE_JOURNAL_READ = "EXIT_CONFIRMATION_JOURNAL_READ";
  private static final String PHASE_JOURNAL_LOAD = "EXIT_CONFIRMATION_JOURNAL_LOAD";

  private final HarnessStore store;
  private final ExecutionExitJournal journal;
  private final Clock clock;
  private final ReentrantLock[] executionLocks = new ReentrantLock[LOCK_STRIPES];
  private final ConcurrentHashMap<UUID, PendingProof> proofs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Failure> failures = new ConcurrentHashMap<>();
  private final Set<String> reportedFileFailures = ConcurrentHashMap.newKeySet();
  private final AtomicReference<DirectoryFailure> directoryFailure = new AtomicReference<>();

  public ExecutionExitConfirmer(HarnessStore store, ExecutionExitJournal journal, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
    for (int i = 0; i < executionLocks.length; i++) {
      executionLocks[i] = new ReentrantLock();
    }
  }

  /** 用真实退出证据确认；journal 写入失败或事实缺失/冲突时抛出，数据库确认失败保留 journal 后原样抛出。 */
  public Result confirm(ExecutionExitFact expected, ExecutionExitState state) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    if (!state.confirmed()) {
      throw new IllegalArgumentException("confirmation requires real exit evidence");
    }
    Instant now = clock.instant();
    Instant observedAt = now.isBefore(expected.registeredAt()) ? expected.registeredAt() : now;
    ExecutionExitJournalRecord candidate = proof(expected, state, observedAt);
    return locked(expected.executionId(), () -> confirmInLock(candidate, PHASE_CONFIRM, now));
  }

  /** 重放内存 proof 与磁盘 snapshot；单条失败保留该记录并继续其它记录。 */
  public void replayPending() {
    Instant now = clock.instant();
    Set<UUID> attempted = new HashSet<>();
    for (UUID executionId : List.copyOf(proofs.keySet())) {
      boolean replayed =
          locked(
              executionId,
              () -> {
                PendingProof pending = proofs.get(executionId);
                if (pending == null || !shouldAttempt(executionId, now)) {
                  return false;
                }
                replayOne(pending.record(), now);
                return true;
              });
      if (replayed) {
        attempted.add(executionId);
      }
    }

    ExecutionExitJournalSnapshot snapshot;
    if (!shouldLoadDirectory(now)) {
      return;
    }
    try {
      snapshot = journal.loadAll();
    } catch (RuntimeException failure) {
      reportDirectoryFailure(now, failure);
      return;
    }
    directoryFailure.set(null);
    reportFileFailures(snapshot.failures());
    for (ExecutionExitJournalRecord record : snapshot.records()) {
      UUID executionId = record.executionId();
      if (attempted.contains(executionId)
          || proofs.containsKey(executionId)
          || !shouldAttempt(executionId, now)) {
        continue;
      }
      boolean replayed =
          locked(
              executionId,
              () -> {
                if (proofs.containsKey(executionId) || !shouldAttempt(executionId, now)) {
                  return false;
                }
                proofs.put(executionId, new PendingProof(record));
                replayOne(record, now);
                return true;
              });
      if (replayed) {
        attempted.add(executionId);
      }
    }
  }

  private <T> T locked(UUID executionId, Supplier<T> action) {
    ReentrantLock lock =
        executionLocks[Math.floorMod(executionId.hashCode(), executionLocks.length)];
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private Result confirmInLock(ExecutionExitJournalRecord candidate, String phase, Instant now) {
    UUID executionId = candidate.executionId();
    PendingProof pending = proofs.get(executionId);
    if (pending == null) {
      pending = new PendingProof(candidate);
      proofs.put(executionId, pending);
    } else if (!pending.record().sameIdentity(candidate)) {
      throw new ExecutionExitJournalConflictException(
          ExecutionExitJournalConflictException.Conflict.IDENTITY,
          "execution " + executionId + " identity cannot be replaced");
    } else if (pending.record().targetState() != candidate.targetState()) {
      throw new ExecutionExitJournalConflictException(
          ExecutionExitJournalConflictException.Conflict.TARGET,
          "execution " + executionId + " target cannot be replaced");
    }

    ExecutionExitJournalRecord canonical;
    try {
      canonical = journal.write(pending.record());
      proofs.put(executionId, new PendingProof(canonical));
    } catch (ExecutionExitJournalConflictException conflict) {
      FailureReason reason =
          conflict.conflict() == ExecutionExitJournalConflictException.Conflict.IDENTITY
              ? FailureReason.IDENTITY_CONFLICT
              : FailureReason.TARGET_CONFLICT;
      throw permanent(executionId, reason, phase, now, conflict);
    } catch (RuntimeException failure) {
      recordFailure(executionId, FailureReason.JOURNAL_WRITE, phase, now, failure);
      throw failure;
    }

    ExecutionExitJournalRecord canonicalProof = canonical;
    ExecutionExitFact confirmedFact = canonicalProof.expected(canonicalProof.confirmedAt());
    ExecutionExitState targetState = canonicalProof.targetState();
    Instant confirmedAt = canonicalProof.confirmedAt();
    Attempt attempt;
    try {
      attempt = store.transaction(tx -> attemptFast(tx, confirmedFact, targetState, confirmedAt));
      if (attempt == Attempt.RETRY_SLOW) {
        attempt = store.transaction(tx -> attemptSlow(tx, confirmedFact, targetState, confirmedAt));
      }
    } catch (RuntimeException failure) {
      recordFailure(executionId, FailureReason.DATABASE, phase, now, failure);
      throw failure;
    }
    // 事务结果中的不变量冲突不是数据库瞬态故障，必须在事务异常分类之外处理。
    return complete(executionId, attempt, phase, now);
  }

  private void replayOne(ExecutionExitJournalRecord record, Instant now) {
    try {
      confirmInLock(record, PHASE_REPLAY, now);
    } catch (RuntimeException failure) {
      if (!failures.containsKey(record.executionId())) {
        recordFailure(record.executionId(), FailureReason.REPLAY, PHASE_REPLAY, now, failure);
      }
    }
  }

  private Result complete(UUID executionId, Attempt attempt, String phase, Instant now) {
    return switch (attempt) {
      case CONFIRMED -> {
        // 只有确认事务成功提交后才删除 journal。
        deleteJournal(executionId, phase, now);
        yield new Result(Outcome.CONFIRMED);
      }
      case THREAD_MISSING -> {
        deleteJournal(executionId, phase, now);
        yield new Result(Outcome.THREAD_MISSING);
      }
      case FACT_MISSING -> throw permanent(
          executionId,
          FailureReason.FACT_MISSING,
          phase,
          now,
          new IllegalStateException(
              "thread still exists but execution " + executionId + " is missing"));
      case CONFLICT -> throw permanent(
          executionId,
          FailureReason.CONFLICT,
          phase,
          now,
          new IllegalArgumentException(
              "execution " + executionId + " identity cannot be replaced"));
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

  private void deleteJournal(UUID executionId, String phase, Instant now) {
    try {
      journal.delete(executionId);
      proofs.remove(executionId);
      failures.remove(executionId);
    } catch (RuntimeException failure) {
      recordFailure(executionId, FailureReason.JOURNAL_DELETE, phase, now, failure);
    }
  }

  private void reportFileFailures(List<ExecutionExitJournalFailure> fileFailures) {
    Set<String> current = new HashSet<>();
    for (ExecutionExitJournalFailure failure : fileFailures) {
      current.add(failure.fileName());
      if (reportedFileFailures.add(failure.fileName())) {
        UUID executionId = failure.canonicalExecutionId();
        log.warn(
            "cannot read execution exit journal entry;"
                + " phase={}; reason={}; executionId={}; exception={}",
            PHASE_JOURNAL_READ,
            failure.reason(),
            executionId == null ? "-" : executionId,
            failure.exceptionClass());
      }
    }
    reportedFileFailures.retainAll(current);
  }

  private void reportDirectoryFailure(Instant now, RuntimeException failure) {
    DirectoryFailure previous = directoryFailure.get();
    int attempts = previous == null ? 1 : previous.attempts() + 1;
    directoryFailure.set(new DirectoryFailure(attempts, now.plusSeconds(backoffSeconds(attempts))));
    if (previous == null) {
      log.warn(
          "cannot read execution exit journal;"
              + " phase={}; reason=DIRECTORY; executionId=-; exception={}",
          PHASE_JOURNAL_LOAD,
          failure.getClass().getSimpleName());
    }
  }

  private boolean shouldLoadDirectory(Instant now) {
    DirectoryFailure failure = directoryFailure.get();
    return failure == null || !now.isBefore(failure.nextAttemptAt());
  }

  private boolean shouldAttempt(UUID executionId, Instant now) {
    Failure failure = failures.get(executionId);
    return failure == null || (!failure.permanent() && !now.isBefore(failure.nextAttemptAt()));
  }

  private RuntimeException permanent(
      UUID executionId, FailureReason reason, String phase, Instant now, RuntimeException failure) {
    recordFailure(executionId, reason, phase, now, failure);
    return failure;
  }

  private void recordFailure(
      UUID executionId, FailureReason reason, String phase, Instant now, Throwable failure) {
    Failure previous = failures.get(executionId);
    boolean sameReason = previous != null && previous.reason() == reason;
    int attempts = sameReason ? previous.attempts() + 1 : 1;
    boolean permanent =
        reason == FailureReason.FACT_MISSING
            || reason == FailureReason.IDENTITY_CONFLICT
            || reason == FailureReason.TARGET_CONFLICT
            || reason == FailureReason.CONFLICT;
    Instant nextAttemptAt =
        permanent ? Instant.MAX : now.plus(Duration.ofSeconds(backoffSeconds(attempts)));
    failures.put(executionId, new Failure(reason, permanent, attempts, nextAttemptAt));
    if (!sameReason) {
      log.warn(
          "cannot resolve execution exit confirmation;"
              + " phase={}; reason={}; executionId={}; exception={}",
          phase,
          reason,
          executionId,
          failure == null ? "-" : failure.getClass().getSimpleName());
    }
  }

  private static long backoffSeconds(int attempts) {
    return BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)];
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

  private enum FailureReason {
    FACT_MISSING,
    IDENTITY_CONFLICT,
    TARGET_CONFLICT,
    CONFLICT,
    JOURNAL_WRITE,
    JOURNAL_DELETE,
    DATABASE,
    REPLAY
  }

  private record PendingProof(ExecutionExitJournalRecord record) {}

  private record DirectoryFailure(int attempts, Instant nextAttemptAt) {}

  private record Failure(
      FailureReason reason, boolean permanent, int attempts, Instant nextAttemptAt) {}
}
