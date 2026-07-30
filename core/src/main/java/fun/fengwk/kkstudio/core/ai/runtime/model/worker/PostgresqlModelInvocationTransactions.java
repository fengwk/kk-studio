package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionTargetStore;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ClaimedModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationUpdateOutcome;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * final-schema PostgreSQL 适配器，实现 Runtime {@link ModelInvocationTransactions}。
 *
 * <p>所有 mutation 走单一 Spring {@code @Transactional} 边界；锁顺序固定为 Thread → ModelInvocation → {@code
 * harness_execution_target}；SQL CAS 谓词与 Java 双重检验。所有失败模式（stale
 * token/attempt/epoch/lease/status/duplicate terminal）以 {@link
 * ModelInvocationUpdateOutcome#LOST_OWNERSHIP} 形式返回；数据库 invariant breach（缺失外键、无法 markRunnable、目标行在
 * owned mutation 中消失）抛运行时异常让 Spring 回滚。
 *
 * <p>{@code harness_execution_target} 是 ModelInvocation 调度的唯一事实：
 *
 * <ul>
 *   <li>{@link #claim} 在完整重校验后 {@code lockDue(MODEL_INVOCATION, id, now)}，验证目标存在且 due；非 due/缺失 返回
 *       {@link Optional#empty()} 且不动 invocation；CAS 成功后把目标 reschedule 到 worker lease until。
 *   <li>{@link #renew} 在 owned 写 lease 成功之后 {@code lock(MODEL_INVOCATION, id)}（any due/future）并
 *       reschedule 到新 until；目标缺失抛异常回滚。
 *   <li>terminal mutation 在写完 status 与 markRunnable 之后 {@code deleteLocked(MODEL_INVOCATION, id)}，再
 *       {@code schedule(THREAD, threadId, null, now)}（THREAD 返回 0 表示已有更早，不报错）。
 *   <li>{@link #scheduleRetry} 在写完 RETRY_WAIT 之后 {@code lock(MODEL_INVOCATION, id)} 并 reschedule 到
 *       {@code nextAttemptAt}。
 *   <li>{@link #recordActivity} 与 {@link #recordSafeStreamSnapshot} 不触碰目标。
 * </ul>
 *
 * <p>本 bean 由 {@code @Service} 暴露给 Runtime，不创建任何 {@code ModelWorker} bean。
 */
@Service
public class PostgresqlModelInvocationTransactions implements ModelInvocationTransactions {

  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Duration MIN_LEASE = Duration.ofMillis(1);

  private final ModelInvocationMapper invocationMapper;
  private final HarnessModelInvocationThreadMapper threadMapper;
  private final ExecutionTargetStore executionTargetStore;

  public PostgresqlModelInvocationTransactions(
      ModelInvocationMapper invocationMapper,
      HarnessModelInvocationThreadMapper threadMapper,
      ExecutionTargetStore executionTargetStore) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.executionTargetStore =
        Objects.requireNonNull(executionTargetStore, "executionTargetStore");
  }

  // ---------- read paths ----------

  @Override
  @Transactional(readOnly = true)
  public Optional<ModelInvocation> findClaimable(long invocationId, Instant now) {
    Objects.requireNonNull(now, "now");
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    ModelInvocationDO row =
        invocationMapper.findClaimable(
            invocationId, ModelInvocationRowConverter.toUtcOffsetDateTime(now));
    return row == null
        ? Optional.empty()
        : Optional.of(ModelInvocationRowConverter.toAggregate(row));
  }

  // ---------- claim ----------

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ClaimedModelInvocation> claim(
      long invocationId,
      String workerToken,
      ModelCallTimeoutPolicy timeoutPolicy,
      Duration workerLeaseDuration,
      Instant now) {
    Objects.requireNonNull(workerToken, "workerToken");
    Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
    Objects.requireNonNull(workerLeaseDuration, "workerLeaseDuration");
    Objects.requireNonNull(now, "now");
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    if (workerToken.isBlank()) {
      throw new IllegalArgumentException("workerToken must not be blank");
    }
    if (workerToken.length() > MAX_TOKEN_LENGTH) {
      throw new IllegalArgumentException("workerToken must be <= " + MAX_TOKEN_LENGTH + " chars");
    }
    if (workerLeaseDuration.compareTo(MIN_LEASE) < 0) {
      throw new IllegalArgumentException("workerLeaseDuration must be >= 1ms");
    }

    // 1) 非锁 peek：只读 threadId 与初始可 claim 性。
    ModelInvocationDO peek =
        invocationMapper.findClaimable(
            invocationId, ModelInvocationRowConverter.toUtcOffsetDateTime(now));
    if (peek == null) {
      return Optional.empty();
    }

    // 2) 锁 Thread（FOR UPDATE）；缺失则视为 invariant breach。
    HarnessModelInvocationThreadDO threadRow = threadMapper.findForUpdate(peek.getThreadId());
    if (threadRow == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocationId);
    }
    if (!Objects.equals(threadRow.getExecutionEpoch(), peek.getExecutionEpoch())) {
      return Optional.empty();
    }

    // 3) 锁 Invocation（FOR UPDATE）并完整重校验。
    ModelInvocationDO row = invocationMapper.findForUpdate(peek.getId(), peek.getThreadId());
    if (row == null) {
      return Optional.empty();
    }
    if (!Objects.equals(row.getExecutionEpoch(), threadRow.getExecutionEpoch())) {
      return Optional.empty();
    }
    int expectedAttempt = row.getAttempt();
    OffsetDateTime nowOffset = ModelInvocationRowConverter.toUtcOffsetDateTime(now);

    // 4) 锁 target（FOR UPDATE）+ due 校验：claim 当前拿到的 due 信号来自这张目标行；非 due 或缺失视为不可 claim，
    // 直接返回 empty，绝不修改 invocation。
    if (executionTargetStore
        .lockDue(ExecutionTargetKind.MODEL_INVOCATION, invocationId, now)
        .isEmpty()) {
      return Optional.empty();
    }

    InvocationStatus status = InvocationStatus.valueOf(row.getStatus());
    if (status == InvocationStatus.QUEUED) {
      OffsetDateTime startedOffset =
          max(nowOffset, Objects.requireNonNull(row.getCreatedAt(), "createdAt"));
      OffsetDateTime leaseUntil =
          addAtPersistencePrecision(startedOffset, workerLeaseDuration, "workerLeaseDuration");
      OffsetDateTime deadlineOffset =
          addAtPersistencePrecision(
              startedOffset, timeoutPolicy.modelCallTimeout(), "modelCallTimeout");
      int updated =
          invocationMapper.claimFromQueued(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              workerToken,
              leaseUntil,
              startedOffset,
              deadlineOffset,
              startedOffset,
              expectedAttempt);
      if (updated != 1) {
        throw new IllegalStateException(
            "queued model claim mutated " + updated + " rows after the target gate passed");
      }
      requireTargetAffected(
          executionTargetStore.rescheduleLocked(
              ExecutionTargetKind.MODEL_INVOCATION, row.getId(), null, leaseUntil.toInstant()),
          "reschedule model invocation target after claim");
      return loadClaimed(row.getId(), row.getThreadId(), workerToken, false);
    }
    if (status == InvocationStatus.RETRY_WAIT) {
      OffsetDateTime leaseUntil =
          addAtPersistencePrecision(
              max(nowOffset, Objects.requireNonNull(row.getStartedAt(), "startedAt")),
              workerLeaseDuration,
              "workerLeaseDuration");
      int updated =
          invocationMapper.claimFromRetryWait(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              expectedAttempt,
              workerToken,
              leaseUntil,
              nowOffset,
              nowOffset);
      if (updated != 1) {
        throw new IllegalStateException(
            "retry model claim mutated " + updated + " rows after the target gate passed");
      }
      requireTargetAffected(
          executionTargetStore.rescheduleLocked(
              ExecutionTargetKind.MODEL_INVOCATION, row.getId(), null, leaseUntil.toInstant()),
          "reschedule model invocation target after retry claim");
      return loadClaimed(row.getId(), row.getThreadId(), workerToken, false);
    }
    if (status == InvocationStatus.RUNNING) {
      String existingToken = Objects.requireNonNull(row.getWorkerToken(), "workerToken");
      OffsetDateTime leaseUntil =
          addAtPersistencePrecision(
              max(nowOffset, Objects.requireNonNull(row.getStartedAt(), "startedAt")),
              workerLeaseDuration,
              "workerLeaseDuration");
      int updated =
          invocationMapper.recoverExpiredLease(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              expectedAttempt,
              existingToken,
              workerToken,
              leaseUntil,
              nowOffset);
      if (updated != 1) {
        throw new IllegalStateException(
            "recovered model claim mutated " + updated + " rows after the target gate passed");
      }
      requireTargetAffected(
          executionTargetStore.rescheduleLocked(
              ExecutionTargetKind.MODEL_INVOCATION, row.getId(), null, leaseUntil.toInstant()),
          "reschedule model invocation target after lease recovery");
      return loadClaimed(row.getId(), row.getThreadId(), workerToken, true);
    }
    return Optional.empty();
  }

  // ---------- claim-following mutations ----------

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome renew(
      ClaimedModelInvocation claimed, Duration workerLeaseDuration, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(workerLeaseDuration, "workerLeaseDuration");
    Objects.requireNonNull(now, "now");
    if (workerLeaseDuration.compareTo(MIN_LEASE) < 0) {
      throw new IllegalArgumentException("workerLeaseDuration must be >= 1ms");
    }
    ModelInvocationDO row = lockAndValidateOwned(claimed, now);
    if (row == null) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    lockOwnedTarget(row.getId());
    OffsetDateTime nowOffset = ModelInvocationRowConverter.toUtcOffsetDateTime(now);
    OffsetDateTime requestedLeaseUntil =
        addAtPersistencePrecision(
            max(nowOffset, Objects.requireNonNull(row.getStartedAt(), "startedAt")),
            workerLeaseDuration,
            "workerLeaseDuration");
    OffsetDateTime leaseUntil =
        max(Objects.requireNonNull(row.getWorkerUntil(), "workerUntil"), requestedLeaseUntil);
    int updated =
        invocationMapper.renewLease(
            claimed.invocation().id(),
            claimed.invocation().threadId(),
            claimed.invocation().executionEpoch(),
            claimed.invocation().attempt(),
            claimed.invocation().workerLease().token(),
            leaseUntil,
            nowOffset);
    if (updated != 1) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.MODEL_INVOCATION,
            claimed.invocation().id(),
            null,
            leaseUntil.toInstant()),
        "reschedule model invocation target after renew");
    return ModelInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome recordActivity(
      ClaimedModelInvocation claimed, Instant activityAt, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(activityAt, "activityAt");
    Objects.requireNonNull(now, "now");
    if (lockAndValidateOwned(claimed, now) == null) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    int updated =
        invocationMapper.recordActivity(
            claimed.invocation().id(),
            claimed.invocation().threadId(),
            claimed.invocation().executionEpoch(),
            claimed.invocation().attempt(),
            claimed.invocation().workerLease().token(),
            ModelInvocationRowConverter.toUtcOffsetDateTime(activityAt),
            ModelInvocationRowConverter.toUtcOffsetDateTime(now));
    return updated == 1
        ? ModelInvocationUpdateOutcome.APPLIED
        : ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome completeSuccess(
      ClaimedModelInvocation claimed,
      ProviderResponse result,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(result, "result");
    return completeTerminal(
        claimed, result, null, lastObservedActivityAt, now, TerminalKind.SUCCESS);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome completeFailure(
      ClaimedModelInvocation claimed,
      ModelInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(error, "error");
    return completeTerminal(
        claimed, null, error, lastObservedActivityAt, now, TerminalKind.FAILURE);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome completeCancelled(
      ClaimedModelInvocation claimed, Instant lastObservedActivityAt, Instant now) {
    return completeTerminal(
        claimed, null, null, lastObservedActivityAt, now, TerminalKind.CANCELLED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome completeUnknown(
      ClaimedModelInvocation claimed,
      ModelInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(error, "error");
    return completeTerminal(
        claimed, null, error, lastObservedActivityAt, now, TerminalKind.UNKNOWN);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome scheduleRetry(
      ClaimedModelInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    Objects.requireNonNull(lastObservedActivityAt, "lastObservedActivityAt");
    Objects.requireNonNull(now, "now");
    ModelInvocationDO row = lockAndValidateOwned(claimed, now);
    if (row == null) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    lockOwnedTarget(row.getId());
    Instant durableActivity =
        Objects.requireNonNull(row.getLastActivityAt(), "lastActivityAt").toInstant();
    Instant observedActivity =
        ModelInvocationRowConverter.toPersistenceInstant(lastObservedActivityAt);
    Instant effectiveActivity =
        durableActivity.isAfter(observedActivity) ? durableActivity : observedActivity;
    Instant persistedNextAttemptAt =
        ModelInvocationRowConverter.toPersistenceInstant(nextAttemptAt);
    Instant deadlineAt = Objects.requireNonNull(row.getDeadlineAt(), "deadlineAt").toInstant();
    if (!persistedNextAttemptAt.isAfter(effectiveActivity)) {
      throw new IllegalArgumentException(
          "nextAttemptAt must be strictly after effective lastActivityAt");
    }
    if (!persistedNextAttemptAt.isBefore(deadlineAt)) {
      throw new IllegalArgumentException("nextAttemptAt must be strictly before deadlineAt");
    }
    int updated =
        invocationMapper.scheduleRetry(
            claimed.invocation().id(),
            claimed.invocation().threadId(),
            claimed.invocation().executionEpoch(),
            claimed.invocation().attempt(),
            claimed.invocation().workerLease().token(),
            ModelInvocationRowConverter.toUtcOffsetDateTime(observedActivity),
            ModelInvocationRowConverter.toUtcOffsetDateTime(persistedNextAttemptAt),
            ModelInvocationRowConverter.toUtcOffsetDateTime(now));
    if (updated != 1) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.MODEL_INVOCATION,
            claimed.invocation().id(),
            null,
            persistedNextAttemptAt),
        "reschedule model invocation target after retry");
    return ModelInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelInvocationUpdateOutcome recordSafeStreamSnapshot(
      ClaimedModelInvocation claimed,
      SafeStreamSnapshot snapshot,
      Instant activityAt,
      Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(activityAt, "activityAt");
    Objects.requireNonNull(now, "now");
    if (claimed.invocation().status() != InvocationStatus.RUNNING) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ModelInvocationDO row = lockAndValidateOwned(claimed, now);
    if (row == null) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // 在 Thread + Invocation 行锁内读取当前 durable 快照，按 prefix-单调判定输入：延长用输入、旧输入保留 durable、
    // 分叉视为非法 invocation state 而显式拒绝；任何情形下都不允许较短 snapshot 覆盖较长 durable snapshot。
    SafeStreamSnapshot durableSnapshot =
        row.getSafeStreamSnapshotJson() == null
            ? SafeStreamSnapshot.EMPTY
            : ModelInvocationRowConverter.decodeSnapshot(row.getSafeStreamSnapshotJson());
    SafeStreamSnapshot effectiveSnapshot;
    try {
      effectiveSnapshot = SafeStreamSnapshotMonotonicity.merge(durableSnapshot, snapshot);
    } catch (SafeStreamSnapshotMonotonicity.IllegalSnapshotForkException error) {
      throw new IllegalStateException(
          "safe stream snapshot fork for invocation " + claimed.invocation().id(), error);
    }
    // SQL CAS 已校验 RUNNING + attempt + lease；防止同一 attempt 内的 race，再补一层单调检查。
    Instant persistedActivity = ModelInvocationRowConverter.toPersistenceInstant(activityAt);
    Instant durableActivity =
        Objects.requireNonNull(row.getLastActivityAt(), "lastActivityAt").toInstant();
    Instant effectiveActivity =
        durableActivity.isAfter(persistedActivity) ? durableActivity : persistedActivity;
    int updated =
        invocationMapper.recordSafeStreamSnapshot(
            claimed.invocation().id(),
            claimed.invocation().threadId(),
            claimed.invocation().executionEpoch(),
            claimed.invocation().attempt(),
            claimed.invocation().workerLease().token(),
            ModelInvocationRowConverter.encodeSnapshot(effectiveSnapshot),
            ModelInvocationRowConverter.toUtcOffsetDateTime(effectiveActivity),
            ModelInvocationRowConverter.toUtcOffsetDateTime(now));
    return updated == 1
        ? ModelInvocationUpdateOutcome.APPLIED
        : ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
  }

  // ---------- helpers ----------

  private ModelInvocationDO lockAndValidateOwned(ClaimedModelInvocation claimed, Instant now) {
    long threadId = claimed.invocation().threadId();
    HarnessModelInvocationThreadDO threadRow = threadMapper.findForUpdate(threadId);
    if (threadRow == null) {
      throw new IllegalStateException(
          "owning thread missing for invocation " + claimed.invocation().id());
    }
    if (!Objects.equals(threadRow.getExecutionEpoch(), claimed.invocation().executionEpoch())) {
      return null;
    }
    ModelInvocationDO row =
        invocationMapper.findForUpdate(claimed.invocation().id(), claimed.invocation().threadId());
    if (row == null) {
      return null;
    }
    // token 必须相等；workerUntil 不比较相等性：renew 单调延长时间后，调用方持有的 ClaimedModelInvocation
    // 仍是旧 until，数据库已经更大；SQL CAS 自身仍校验 worker_until > now，因此租约有效性是最终权威。
    if (row.getWorkerToken() == null
        || !row.getWorkerToken().equals(claimed.invocation().workerLease().token())) {
      return null;
    }
    if (!Objects.equals(row.getId(), claimed.invocation().id())) {
      return null;
    }
    if (!Objects.equals(row.getThreadId(), claimed.invocation().threadId())) {
      return null;
    }
    if (!Objects.equals(row.getExecutionEpoch(), claimed.invocation().executionEpoch())) {
      return null;
    }
    if (!Objects.equals(row.getAttempt(), claimed.invocation().attempt())) {
      return null;
    }
    if (!row.getStatus().equals(claimed.invocation().status().name())) {
      return null;
    }
    Instant durableUntil = Objects.requireNonNull(row.getWorkerUntil()).toInstant();
    if (!durableUntil.isAfter(ModelInvocationRowConverter.toPersistenceInstant(now))) {
      return null;
    }
    return row;
  }

  private Optional<ClaimedModelInvocation> loadClaimed(
      long invocationId, long threadId, String workerToken, boolean recoveredLease) {
    ModelInvocationDO refreshed = invocationMapper.findForUpdate(invocationId, threadId);
    if (refreshed == null) {
      throw new IllegalStateException("claimed invocation disappeared: " + invocationId);
    }
    ModelInvocation invocation = ModelInvocationRowConverter.toAggregate(refreshed);
    if (invocation.status() != InvocationStatus.RUNNING
        || invocation.workerLease() == null
        || !invocation.workerLease().token().equals(workerToken)) {
      throw new IllegalStateException("claim result does not match requested ownership");
    }
    return Optional.of(new ClaimedModelInvocation(invocation, recoveredLease));
  }

  private static OffsetDateTime addAtPersistencePrecision(
      OffsetDateTime base, Duration duration, String name) {
    OffsetDateTime target =
        ModelInvocationRowConverter.toUtcOffsetDateTime(base.toInstant().plus(duration));
    if (!target.isAfter(base)) {
      throw new IllegalArgumentException(name + " must advance PostgreSQL time by at least 1ms");
    }
    return target;
  }

  private static OffsetDateTime max(OffsetDateTime left, OffsetDateTime right) {
    return left.isAfter(right) ? left : right;
  }

  private ModelInvocationUpdateOutcome completeTerminal(
      ClaimedModelInvocation claimed,
      ProviderResponse result,
      ModelInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      TerminalKind kind) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(lastObservedActivityAt, "lastObservedActivityAt");
    Objects.requireNonNull(now, "now");
    ModelInvocationDO row = lockAndValidateOwned(claimed, now);
    if (row == null) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    Instant persistedNow = ModelInvocationRowConverter.toPersistenceInstant(now);
    Instant persistedObserved =
        ModelInvocationRowConverter.toPersistenceInstant(lastObservedActivityAt);
    Instant durableActivity =
        Objects.requireNonNull(row.getLastActivityAt(), "lastActivityAt").toInstant();
    Instant finishedInstant = max(persistedNow, max(persistedObserved, durableActivity));
    OffsetDateTime finishedOffset =
        ModelInvocationRowConverter.toUtcOffsetDateTime(finishedInstant);
    OffsetDateTime lastObservedOffset =
        ModelInvocationRowConverter.toUtcOffsetDateTime(persistedObserved);
    OffsetDateTime nowOffset = ModelInvocationRowConverter.toUtcOffsetDateTime(persistedNow);
    lockOwnedTarget(row.getId());

    int updated;
    if (kind == TerminalKind.SUCCESS) {
      updated =
          invocationMapper.completeSuccess(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              claimed.invocation().attempt(),
              claimed.invocation().workerLease().token(),
              ModelInvocationRowConverter.encodeResponse(result),
              lastObservedOffset,
              finishedOffset,
              nowOffset);
    } else if (kind == TerminalKind.FAILURE) {
      updated =
          invocationMapper.completeFailure(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              claimed.invocation().attempt(),
              claimed.invocation().workerLease().token(),
              ModelInvocationRowConverter.encodeError(error),
              lastObservedOffset,
              finishedOffset,
              nowOffset);
    } else if (kind == TerminalKind.CANCELLED) {
      updated =
          invocationMapper.completeCancelled(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              claimed.invocation().attempt(),
              claimed.invocation().workerLease().token(),
              lastObservedOffset,
              finishedOffset,
              nowOffset);
    } else {
      updated =
          invocationMapper.completeUnknown(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              claimed.invocation().attempt(),
              claimed.invocation().workerLease().token(),
              ModelInvocationRowConverter.encodeError(error),
              lastObservedOffset,
              finishedOffset,
              nowOffset);
    }
    if (updated != 1) {
      return ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    }

    int markRunnable =
        threadMapper.markRunnable(row.getThreadId(), row.getExecutionEpoch(), finishedOffset);
    if (markRunnable != 1) {
      // 这是真正的 invariant breach（FK 缺失、Thread execution_epoch 不匹配），让 Spring 回滚。
      throw new IllegalStateException(
          "cannot mark thread "
              + row.getThreadId()
              + " runnable while finalising invocation "
              + row.getId());
    }
    // Durable target wiring: 与上面三个写入同事务内（i）删除 MODEL_INVOCATION target，（ii）schedule
    // THREAD target。deleteLocked 必须 affected==1；schedule 返回 0 表示已经有更早 THREAD target，符合
    // earliest-wins 语义，不视为错误。
    requireTargetAffected(
        executionTargetStore.deleteLocked(ExecutionTargetKind.MODEL_INVOCATION, row.getId()),
        "delete model invocation target after terminal");
    executionTargetStore.schedule(
        ExecutionTargetKind.THREAD, row.getThreadId(), null, persistedNow);
    return ModelInvocationUpdateOutcome.APPLIED;
  }

  private void lockOwnedTarget(long invocationId) {
    if (executionTargetStore.lock(ExecutionTargetKind.MODEL_INVOCATION, invocationId).isEmpty()) {
      throw new IllegalStateException(
          "owned model invocation is missing its watchdog target: " + invocationId);
    }
  }

  private static void requireTargetAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }

  private enum TerminalKind {
    SUCCESS,
    FAILURE,
    CANCELLED,
    UNKNOWN
  }

  private static Instant max(Instant left, Instant right) {
    return left.isAfter(right) ? left : right;
  }
}
