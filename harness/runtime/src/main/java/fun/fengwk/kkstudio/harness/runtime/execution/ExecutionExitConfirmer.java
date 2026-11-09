package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Model / Tool / local / remote 共用的退出确认入口与执行日志重放器。
 *
 * <p>数据库任何读取或提交之前先把正向 proof 冻结到本机两阶段 journal；数据库不可用时文件仍在，重启后可重放。确认与 Join 结算提交后才 删除记录。每轮重放把本进程 proof
 * 与磁盘 journal 的 EXIT_PROOF 记录按 execution 身份合并为同一待重放集合，每个 execution 每轮最多尝试一次； 目录枚举失败只跳过本轮磁盘记录，不阻断本进程
 * proof。快路径只锁本 Thread，Thread 为 RUNNABLE 且没有未完成 Join 时只推进 version；需要祖先结算时先结束只读探测事务，再以 {@link
 * ExecutionExitLedger#lockAncestorChain} 作为新事务第一把锁重新确认。
 *
 * <p>启动恢复 {@link #recoverPreviousRun()} 冻结上一次运行遗留的 PREPARED 集合，先重放 EXIT_PROOF，再按 Thread
 * 锁与权威数据库状态对账旧 PREPARED：事实存在且未确认时确认 {@link
 * ExecutionExitState#OWNER_EXITED}，事实已有结论或从未登记时清理记录，身份冲突时原样保留并报告。 冻结集合由同一个重放器继续重试，本轮读不到的当前进程
 * PREPARED 永远不会被当成上一进程退出。
 *
 * <p>同一 execution 的确认在进程内按稳定 stripe 串行化，事实与 version 推进幂等，并始终复用待确认记录的第一次 canonical proof
 * 的身份、目标和确认时刻。首次 journal 写入失败时 proof 只保留在本进程内等待重试，不能宣称跨进程 durable。事实缺失或 canonical proof 与
 * journal/数据库的真实 identity/target 冲突只对该 execution 每 30 秒低频核对；被拒绝的新输入不改变原 proof。其它数据库或文件失败按 {@link
 * #BACKOFF_SECONDS} 退避，成功后重置。
 */
@Slf4j
public final class ExecutionExitConfirmer {

  private static final int LOCK_STRIPES = 64;
  private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
  private static final String PHASE_CONFIRM = "EXIT_CONFIRMATION_CONFIRM";
  private static final String PHASE_REPLAY = "EXIT_CONFIRMATION_REPLAY";
  private static final String PHASE_RECOVER = "EXIT_CONFIRMATION_OWNER_RECOVERY";
  private static final String PHASE_JOURNAL_READ = "EXIT_CONFIRMATION_JOURNAL_READ";
  private static final String PHASE_JOURNAL_LOAD = "EXIT_CONFIRMATION_JOURNAL_LOAD";

  private final HarnessStore store;
  private final ExecutionExitJournal journal;
  private final Clock clock;
  private final ReentrantLock[] executionLocks = new ReentrantLock[LOCK_STRIPES];
  private final ConcurrentHashMap<UUID, PendingProof> proofs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ExecutionExitJournalRecord> frozenPrepared =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Failure> failures = new ConcurrentHashMap<>();
  private final Set<String> reportedFileFailures = ConcurrentHashMap.newKeySet();
  private final AtomicReference<DirectoryFailure> directoryFailure = new AtomicReference<>();
  private final AtomicBoolean recovered = new AtomicBoolean();

  public ExecutionExitConfirmer(HarnessStore store, ExecutionExitJournal journal, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
    for (int i = 0; i < executionLocks.length; i++) {
      executionLocks[i] = new ReentrantLock();
    }
  }

  /**
   * 首轮恢复：冻结上一次运行遗留的 PREPARED 集合并完成对账。必须在允许任何新执行登记之前调用且只调用一次。
   *
   * <p>目录无法枚举时抛出，调用方必须拒绝继续启动；数据库瞬态失败只保留该记录并交给同一个重放器继续重试，不阻塞其它健康记录。
   */
  public LocalExecutionRecovery recoverPreviousRun() {
    if (!recovered.compareAndSet(false, true)) {
      throw new IllegalStateException("execution exit recovery may only run once");
    }
    Instant now = clock.instant();
    ExecutionExitJournalSnapshot snapshot;
    try {
      snapshot = journal.loadAll();
      directoryFailure.set(null);
    } catch (RuntimeException failure) {
      reportDirectoryFailure(now, failure);
      throw failure;
    }
    reportFileFailures(snapshot.failures());
    List<ExecutionExitJournalRecord> prepared = new ArrayList<>();
    Map<UUID, ReplayCandidate> evidence = new LinkedHashMap<>();
    for (ExecutionExitJournalRecord record : snapshot.records()) {
      if (record.stage() == ExecutionExitJournalStage.PREPARED) {
        prepared.add(record);
      } else {
        evidence.putIfAbsent(
            record.executionId(), ReplayCandidate.persisted(record.executionId(), record));
      }
    }
    // 先重放既有真实证据，再对账旧 PREPARED。
    for (ReplayCandidate candidate : evidence.values()) {
      replay(candidate, now);
    }
    List<UUID> ownerExited = new ArrayList<>();
    List<UUID> cleaned = new ArrayList<>();
    List<UUID> retained = new ArrayList<>();
    for (ExecutionExitJournalRecord record : prepared) {
      frozenPrepared.put(record.executionId(), record);
      switch (reconcilePrepared(record, PHASE_RECOVER, now)) {
        case OWNER_EXITED -> ownerExited.add(record.executionId());
        case CLEANED -> cleaned.add(record.executionId());
        case RETAINED -> retained.add(record.executionId());
      }
    }
    log.info(
        "execution exit recovery finished; evidence={}; prepared={}; ownerExited={};"
            + " cleaned={}; retained={}; unreadableFiles={}",
        evidence.size(),
        prepared.size(),
        ownerExited.size(),
        cleaned.size(),
        retained.size(),
        snapshot.failures().size());
    return new LocalExecutionRecovery(ownerExited, cleaned, retained);
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

  /** 重放待确认的退出证明；单条失败保留该记录并继续其它记录。 */
  public void replayPending() {
    replayNow(clock.instant(), true);
  }

  private void replayNow(Instant now, boolean includeFrozenPrepared) {
    Map<UUID, ReplayCandidate> candidates = new LinkedHashMap<>();
    for (UUID executionId : List.copyOf(proofs.keySet())) {
      if (proofs.containsKey(executionId)) {
        candidates.put(executionId, ReplayCandidate.inMemory(executionId));
      }
    }
    if (shouldLoadDirectory(now)) {
      try {
        ExecutionExitJournalSnapshot snapshot = journal.loadAll();
        directoryFailure.set(null);
        reportFileFailures(snapshot.failures());
        for (ExecutionExitJournalRecord record : snapshot.records()) {
          // 当前进程新登记的 PREPARED 绝不是退出证据，只有冻结的上一进程集合才会被对账。
          if (record.stage() != ExecutionExitJournalStage.EXIT_PROOF) {
            continue;
          }
          candidates.putIfAbsent(
              record.executionId(), ReplayCandidate.persisted(record.executionId(), record));
        }
      } catch (RuntimeException failure) {
        reportDirectoryFailure(now, failure);
      }
    }
    for (ReplayCandidate candidate : candidates.values()) {
      replay(candidate, now);
    }
    if (includeFrozenPrepared) {
      for (ExecutionExitJournalRecord record : List.copyOf(frozenPrepared.values())) {
        if (shouldAttempt(record.executionId(), now)) {
          reconcilePrepared(record, PHASE_REPLAY, now);
        }
      }
    }
  }

  private void replay(ReplayCandidate candidate, Instant now) {
    UUID executionId = candidate.executionId();
    if (!shouldAttempt(executionId, now)) {
      return;
    }
    locked(
        executionId,
        () -> {
          PendingProof current = proofs.get(executionId);
          if (current != null) {
            // 磁盘记录来临前本进程已有更权威的 proof，本轮由内存候选处理。
            if (!candidate.inMemory()) {
              return false;
            }
          } else {
            // 内存 proof 在等待锁期间已完成并删除时不再重放；磁盘记录此时才装入。
            if (candidate.inMemory()) {
              return false;
            }
            current = new PendingProof(candidate.journalRecord());
            proofs.put(executionId, current);
          }
          if (shouldAttempt(executionId, now)) {
            replayOne(current.record(), now);
          }
          return true;
        });
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

  /**
   * 旧 PREPARED 的权威对账：在 Thread 锁内读取真实事实，离开事务后再做文件与确认操作。
   *
   * <p>返回 {@link PreparedOutcome#RETAINED} 时记录仍保留在冻结集合中，由同一个重放器按退避继续处理。
   */
  private PreparedOutcome reconcilePrepared(
      ExecutionExitJournalRecord record, String phase, Instant now) {
    return locked(
        record.executionId(),
        () -> {
          PreparedFactLookup lookup;
          try {
            lookup = store.transaction(tx -> lookupPreparedFact(tx, record));
          } catch (RuntimeException failure) {
            recordFailure(record.executionId(), FailureReason.DATABASE, phase, now, failure);
            return PreparedOutcome.RETAINED;
          }
          return switch (lookup.kind()) {
            case THREAD_MISSING, FACT_MISSING -> cleanupPrepared(record, phase, now);
            case SETTLED -> cleanupPrepared(record, phase, now);
            case CONFLICT -> {
              recordFailure(
                  record.executionId(),
                  FailureReason.IDENTITY_CONFLICT,
                  phase,
                  now,
                  new IllegalStateException(
                      "execution "
                          + record.executionId()
                          + " journal identity does not match the durable fact"));
              yield PreparedOutcome.RETAINED;
            }
            case UNCONFIRMED -> confirmOwnerExit(record, lookup, phase, now);
          };
        });
  }

  private PreparedFactLookup lookupPreparedFact(
      HarnessStore.Transaction tx, ExecutionExitJournalRecord record) {
    if (tx.threads().lock(record.threadId()).isEmpty()) {
      return PreparedFactLookup.threadMissing();
    }
    ExecutionExitFact fact = tx.exits().find(record.executionId()).orElse(null);
    if (fact == null) {
      return PreparedFactLookup.factMissing();
    }
    if (!record.matchesFactIdentity(fact)) {
      return PreparedFactLookup.conflict();
    }
    if (fact.state().confirmed()) {
      return PreparedFactLookup.settled(fact);
    }
    return PreparedFactLookup.unconfirmed(fact);
  }

  /** 数据库已有结论或从未登记：清理 PREPARED，不改写任何既有证据。 */
  private PreparedOutcome cleanupPrepared(
      ExecutionExitJournalRecord record, String phase, Instant now) {
    return deleteJournal(record.executionId(), phase, now)
        ? PreparedOutcome.CLEANED
        : PreparedOutcome.RETAINED;
  }

  /** 事实存在且未确认：走现有一致性事务确认本地所有者已退出。 */
  private PreparedOutcome confirmOwnerExit(
      ExecutionExitJournalRecord record, PreparedFactLookup lookup, String phase, Instant now) {
    try {
      confirm(lookup.fact(), ExecutionExitState.OWNER_EXITED);
      return PreparedOutcome.OWNER_EXITED;
    } catch (ExecutionExitJournalConflictException conflict) {
      recordFailure(record.executionId(), conflictReason(conflict), phase, now, conflict);
      return PreparedOutcome.RETAINED;
    } catch (RuntimeException failure) {
      // confirm 已按原因记录退避；冻结记录保留，由同一个重放器继续核对。
      return PreparedOutcome.RETAINED;
    }
  }

  private Result confirmInLock(ExecutionExitJournalRecord candidate, String phase, Instant now) {
    UUID executionId = candidate.executionId();
    PendingProof pending = proofs.get(executionId);
    if (pending != null && !pending.record().sameIdentity(candidate)) {
      throw new ExecutionExitJournalConflictException(
          ExecutionExitJournalConflictException.Conflict.IDENTITY,
          "execution " + executionId + " identity cannot be replaced");
    }
    if (pending != null && pending.record().targetState() != candidate.targetState()) {
      throw new ExecutionExitJournalConflictException(
          ExecutionExitJournalConflictException.Conflict.TARGET,
          "execution " + executionId + " target cannot be replaced");
    }

    ExecutionExitJournalRecord offered = pending == null ? candidate : pending.record();
    ExecutionExitJournalRecord canonical;
    try {
      canonical = journal.write(offered);
      proofs.put(executionId, new PendingProof(canonical));
    } catch (ExecutionExitJournalConflictException conflict) {
      if (pending == null) {
        // 新输入未被 journal 接纳，不得阻塞磁盘已有 canonical proof 的正常重放。
        throw conflict;
      }
      throw invariantFailure(executionId, conflictReason(conflict), phase, now, conflict);
    } catch (RuntimeException failure) {
      proofs.put(executionId, new PendingProof(offered));
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
      case FACT_MISSING -> throw invariantFailure(
          executionId,
          FailureReason.FACT_MISSING,
          phase,
          now,
          new IllegalStateException(
              "thread still exists but execution " + executionId + " is missing"));
      case CONFLICT -> throw invariantFailure(
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
    ThreadState thread = tx.threads().lock(expected.threadId()).orElse(null);
    if (thread == null) {
      return Attempt.THREAD_MISSING;
    }
    if (thread.executionControl() != ThreadExecutionControl.RUNNABLE
        || !tx.joins().loadIncomplete(expected.threadId()).isEmpty()) {
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
    if (tx.threads().lock(expected.threadId()).isEmpty()) {
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
    ExecutionExitFact current = tx.exits().find(expected.executionId()).orElse(null);
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
    return ExecutionExitJournalRecord.exitProof(expected, state, observedAt);
  }

  private boolean deleteJournal(UUID executionId, String phase, Instant now) {
    try {
      journal.delete(executionId);
      proofs.remove(executionId);
      frozenPrepared.remove(executionId);
      failures.remove(executionId);
      return true;
    } catch (RuntimeException failure) {
      recordFailure(executionId, FailureReason.JOURNAL_DELETE, phase, now, failure);
      return false;
    }
  }

  private static FailureReason conflictReason(ExecutionExitJournalConflictException conflict) {
    return switch (conflict.conflict()) {
      case IDENTITY -> FailureReason.IDENTITY_CONFLICT;
      case TARGET, STAGE -> FailureReason.TARGET_CONFLICT;
    };
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
    return failure == null || !now.isBefore(failure.nextAttemptAt());
  }

  private RuntimeException invariantFailure(
      UUID executionId, FailureReason reason, String phase, Instant now, RuntimeException failure) {
    recordFailure(executionId, reason, phase, now, failure);
    return failure;
  }

  private void recordFailure(
      UUID executionId, FailureReason reason, String phase, Instant now, Throwable failure) {
    Failure previous = failures.get(executionId);
    boolean sameReason = previous != null && previous.reason() == reason;
    int attempts = sameReason ? previous.attempts() + 1 : 1;
    boolean invariantFailure =
        reason == FailureReason.FACT_MISSING
            || reason == FailureReason.IDENTITY_CONFLICT
            || reason == FailureReason.TARGET_CONFLICT
            || reason == FailureReason.CONFLICT;
    // 不变量错误不高频重放，但仍需识别人工修复和合法深删，不能永久停止核对。
    int backoffStep = invariantFailure ? BACKOFF_SECONDS.length : attempts;
    Instant nextAttemptAt = now.plusSeconds(backoffSeconds(backoffStep));
    failures.put(executionId, new Failure(reason, attempts, nextAttemptAt));
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

  /** 旧 PREPARED 的一轮对账结果；只有 OWNER_EXITED 与 CLEANED 会把记录从冻结集合移除。 */
  private enum PreparedOutcome {
    OWNER_EXITED,
    CLEANED,
    RETAINED
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

  /** Thread 锁内读到的旧 PREPARED 权威状态；事实只在 UNCONFIRMED / SETTLED 时非空。 */
  private record PreparedFactLookup(Kind kind, ExecutionExitFact fact) {

    private enum Kind {
      THREAD_MISSING,
      FACT_MISSING,
      CONFLICT,
      SETTLED,
      UNCONFIRMED
    }

    private static PreparedFactLookup threadMissing() {
      return new PreparedFactLookup(Kind.THREAD_MISSING, null);
    }

    private static PreparedFactLookup factMissing() {
      return new PreparedFactLookup(Kind.FACT_MISSING, null);
    }

    private static PreparedFactLookup conflict() {
      return new PreparedFactLookup(Kind.CONFLICT, null);
    }

    private static PreparedFactLookup settled(ExecutionExitFact fact) {
      return new PreparedFactLookup(Kind.SETTLED, fact);
    }

    private static PreparedFactLookup unconfirmed(ExecutionExitFact fact) {
      return new PreparedFactLookup(Kind.UNCONFIRMED, fact);
    }
  }

  private record PendingProof(ExecutionExitJournalRecord record) {}

  /** 一轮重放中的候选：内存 proof 优先，磁盘记录只补足本进程还没有的执行。 */
  private record ReplayCandidate(
      UUID executionId, boolean inMemory, ExecutionExitJournalRecord journalRecord) {

    private static ReplayCandidate inMemory(UUID executionId) {
      return new ReplayCandidate(executionId, true, null);
    }

    private static ReplayCandidate persisted(
        UUID executionId, ExecutionExitJournalRecord journalRecord) {
      return new ReplayCandidate(executionId, false, journalRecord);
    }
  }

  private record DirectoryFailure(int attempts, Instant nextAttemptAt) {}

  private record Failure(FailureReason reason, int attempts, Instant nextAttemptAt) {}
}
