package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore;
import fun.fengwk.kkstudio.core.harness.model.worker.HarnessModelInvocationThreadDO;
import fun.fengwk.kkstudio.core.harness.model.worker.HarnessModelInvocationThreadMapper;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL ToolInvocation worker transaction adapter that drives durable scheduling through the
 * single {@code harness_execution_target} queue.
 *
 * <p>Every mutation acquires row locks in the order Thread -> ToolInvocation -> target, then
 * advances or deletes the {@link ExecutionTargetKind#TOOL_INVOCATION} row in the same transaction.
 * Terminal mutations additionally mark the owning Thread runnable and reschedule the durable Thread
 * target.
 *
 * <p>Claim returns {@link Optional#empty()} when the target is absent or not due; an owned mutation
 * whose target row has disappeared throws {@link IllegalStateException} so the transaction rolls
 * back rather than persisting a half-applied state.
 */
@Service
public class PostgresqlToolInvocationTransactions implements ToolInvocationTransactions {

  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(5);

  private final PostgresqlToolInvocationMapper invocationMapper;
  private final HarnessModelInvocationThreadMapper threadMapper;
  private final ExecutionTargetStore executionTargetStore;

  public PostgresqlToolInvocationTransactions(
      PostgresqlToolInvocationMapper invocationMapper,
      HarnessModelInvocationThreadMapper threadMapper,
      ExecutionTargetStore executionTargetStore) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.executionTargetStore =
        Objects.requireNonNull(executionTargetStore, "executionTargetStore");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ClaimedToolInvocation> claim(
      long invocationId, String workerToken, Duration workerLeaseDuration, Instant now) {
    requirePositive(invocationId, "invocationId");
    requireToken(workerToken);
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    Instant persistedNow = persistence(now);

    // Step 1: thread row lock + epoch pre-read.
    ToolInvocationDO peek = invocationMapper.findClaimable(invocationId, offset(persistedNow));
    if (peek == null) {
      return Optional.empty();
    }
    HarnessModelInvocationThreadDO thread = threadMapper.findForUpdate(peek.getThreadId());
    if (thread == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocationId);
    }
    if (!Objects.equals(thread.getExecutionEpoch(), peek.getExecutionEpoch())) {
      return Optional.empty();
    }

    // Step 2: tool invocation row lock + validation (no mutation yet).
    ToolInvocationDO row = invocationMapper.findForUpdate(invocationId, peek.getThreadId());
    if (row == null || !Objects.equals(row.getExecutionEpoch(), thread.getExecutionEpoch())) {
      return Optional.empty();
    }
    InvocationStatus status = InvocationStatus.valueOf(row.getStatus());
    if (status != InvocationStatus.QUEUED
        && status != InvocationStatus.RETRY_WAIT
        && status != InvocationStatus.RUNNING) {
      return Optional.empty();
    }

    // Step 3: target gate. If the target row is absent or not yet due, the transaction commits
    // with no row mutations, leaving the QUEUED / RETRY_WAIT / RUNNING invocation unchanged.
    if (executionTargetStore
        .lockDue(ExecutionTargetKind.TOOL_INVOCATION, invocationId, persistedNow)
        .isEmpty()) {
      return Optional.empty();
    }

    // Step 4: status transition (status transition is now conditional on target lockDue success).
    OffsetDateTime nowOffset = offset(persistedNow);
    OffsetDateTime leaseUntil = advance(persistedNow, workerLeaseDuration, "workerLeaseDuration");
    int affected;
    boolean recovered = false;
    if (status == InvocationStatus.QUEUED) {
      Instant startedAt = max(persistedNow, row.getCreatedAt().toInstant());
      Duration descriptorTimeout =
          ToolInvocationRowConverter.toAggregate(row).descriptor().timeout();
      Duration executionTimeout =
          descriptorTimeout.isZero() ? DEFAULT_EXECUTION_TIMEOUT : descriptorTimeout;
      affected =
          invocationMapper.claimQueued(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              workerToken,
              advance(startedAt, workerLeaseDuration, "workerLeaseDuration"),
              offset(startedAt),
              advance(startedAt, executionTimeout, "executionTimeout"));
    } else if (status == InvocationStatus.RETRY_WAIT) {
      affected =
          invocationMapper.claimRetry(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              workerToken,
              leaseUntil,
              nowOffset);
    } else {
      affected =
          invocationMapper.recoverExpired(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              Objects.requireNonNull(row.getWorkerToken(), "workerToken"),
              workerToken,
              leaseUntil,
              nowOffset);
      recovered = true;
    }
    if (affected != 1) {
      throw new IllegalStateException(
          "claim mutated " + affected + " rows after the target gate passed");
    }

    // Step 5: target advance + final ownership check.
    ToolInvocationDO claimed = invocationMapper.findForUpdate(row.getId(), row.getThreadId());
    if (claimed == null) {
      throw new IllegalStateException("claimed invocation disappeared: " + row.getId());
    }
    ToolInvocation aggregate = ToolInvocationRowConverter.toAggregate(claimed);
    if (aggregate.status() != InvocationStatus.RUNNING
        || aggregate.workerLease() == null
        || !aggregate.workerLease().token().equals(workerToken)) {
      throw new IllegalStateException("claim result does not match requested ownership");
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION,
            invocationId,
            routeKey(row),
            aggregate.workerLease().until()),
        "reschedule tool invocation target after claim");
    return Optional.of(new ClaimedToolInvocation(aggregate, status, recovered));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome renew(
      ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    Instant persistedNow = persistence(now);
    Instant leaseBase =
        max(
            persistedNow,
            row.getStartedAt() == null ? persistedNow : row.getStartedAt().toInstant());
    Instant requestedLeaseDeadline =
        advance(leaseBase, workerLeaseDuration, "workerLeaseDuration").toInstant();
    Instant leaseDeadline = max(row.getWorkerUntil().toInstant(), requestedLeaseDeadline);
    int affected =
        invocationMapper.renew(
            row.getId(),
            row.getThreadId(),
            row.getExecutionEpoch(),
            row.getAttempt(),
            claimed.invocation().workerLease().token(),
            offset(leaseDeadline),
            offset(persistedNow));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, row.getId(), routeKey(row), leaseDeadline),
        "reschedule tool invocation target after renew");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome recordActivity(
      ClaimedToolInvocation claimed, Instant activityAt, Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocation invocation = claimed.invocation();
    return outcome(
        invocationMapper.recordActivity(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            offset(activityAt),
            offset(persistence(now))));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome releaseUnstarted(
      ClaimedToolInvocation claimed, Instant nextAttemptAt, Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    ToolInvocation invocation = claimed.invocation();
    InvocationStatus previousStatus = claimed.previousStatus();
    int affected;
    Instant persistedNow = persistence(now);
    Instant rescheduleAt;
    if (previousStatus == InvocationStatus.QUEUED) {
      if (nextAttemptAt != null || invocation.attempt() != 1) {
        throw new IllegalArgumentException(
            "QUEUED release requires attempt 1 and no nextAttemptAt");
      }
      affected =
          invocationMapper.releaseUnstartedQueued(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              offset(persistedNow));
      rescheduleAt = persistedNow;
    } else if (previousStatus == InvocationStatus.RETRY_WAIT) {
      if (invocation.attempt() <= 1 || nextAttemptAt == null) {
        throw new IllegalArgumentException(
            "RETRY_WAIT release requires a previous attempt and nextAttemptAt");
      }
      Instant next = persistence(nextAttemptAt);
      if (!next.isAfter(row.getLastActivityAt().toInstant())
          || !next.isBefore(row.getDeadlineAt().toInstant())) {
        throw new IllegalArgumentException(
            "nextAttemptAt must be after activity and before deadline");
      }
      affected =
          invocationMapper.releaseUnstartedRetry(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.attempt() - 1,
              invocation.workerLease().token(),
              offset(next),
              offset(persistedNow));
      rescheduleAt = next;
    } else {
      throw new IllegalArgumentException("previousStatus must be QUEUED or RETRY_WAIT");
    }
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), routeKey(row), rescheduleAt),
        "reschedule tool invocation target after releaseUnstarted");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeSuccess(
      ClaimedToolInvocation claimed,
      Supplier<ToolResult> resultSupplier,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(resultSupplier, "resultSupplier");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolResult result =
        Objects.requireNonNull(resultSupplier.get(), "resultSupplier returned null");
    if (!claimed.invocation().toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("result.toolCallId must match the claimed invocation");
    }
    return applyTerminal(
        claimed,
        locked.row(),
        result,
        null,
        lastObservedActivityAt,
        now,
        InvocationStatus.SUCCEEDED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeFailure(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    return complete(
        claimed,
        null,
        Objects.requireNonNull(error),
        lastObservedActivityAt,
        now,
        InvocationStatus.FAILED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeCancelled(
      ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now) {
    return complete(claimed, null, null, lastObservedActivityAt, now, InvocationStatus.CANCELLED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeUnknown(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    return complete(
        claimed,
        null,
        Objects.requireNonNull(error),
        lastObservedActivityAt,
        now,
        InvocationStatus.UNKNOWN);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome scheduleRetry(
      ClaimedToolInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    Instant effectiveActivity =
        max(row.getLastActivityAt().toInstant(), persistence(lastObservedActivityAt));
    Instant next = persistence(nextAttemptAt);
    if (!next.isAfter(effectiveActivity) || !next.isBefore(row.getDeadlineAt().toInstant())) {
      throw new IllegalArgumentException(
          "nextAttemptAt must be after effective activity and before deadline");
    }
    ToolInvocation invocation = claimed.invocation();
    ToolInvocationUpdateOutcome outcome =
        outcome(
            invocationMapper.scheduleRetry(
                invocation.id(),
                invocation.threadId(),
                invocation.executionEpoch(),
                invocation.attempt(),
                invocation.workerLease().token(),
                offset(lastObservedActivityAt),
                offset(next),
                offset(persistence(now))));
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      return outcome;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), routeKey(row), next),
        "reschedule tool invocation target after scheduleRetry");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  private ToolInvocationUpdateOutcome complete(
      ClaimedToolInvocation claimed,
      ToolResult result,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      InvocationStatus terminalStatus) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    return applyTerminal(
        claimed, locked.row(), result, error, lastObservedActivityAt, now, terminalStatus);
  }

  private ToolInvocationUpdateOutcome applyTerminal(
      ClaimedToolInvocation claimed,
      ToolInvocationDO row,
      ToolResult result,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      InvocationStatus terminalStatus) {
    Instant observed = persistence(lastObservedActivityAt);
    Instant persistedNow = persistence(now);
    Instant finished = max(persistedNow, max(observed, row.getLastActivityAt().toInstant()));
    ToolInvocation invocation = claimed.invocation();
    int affected;
    if (terminalStatus == InvocationStatus.SUCCEEDED) {
      affected =
          invocationMapper.completeSuccess(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              ToolInvocationRowConverter.encodeResult(result),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    } else if (terminalStatus == InvocationStatus.CANCELLED) {
      affected =
          invocationMapper.completeCancelled(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    } else {
      affected =
          invocationMapper.completeError(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              terminalStatus.name(),
              ToolInvocationRowConverter.encodeError(error),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    }
    if (affected != 1) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    if (threadMapper.markRunnable(
            invocation.threadId(), invocation.executionEpoch(), offset(finished))
        != 1) {
      throw new IllegalStateException("cannot mark owning thread runnable");
    }
    requireTargetAffected(
        executionTargetStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()),
        "delete tool invocation target after terminal write");
    // THREAD target scheduling is best-effort: an earlier due time wins and is acceptable.
    executionTargetStore.schedule(
        ExecutionTargetKind.THREAD, invocation.threadId(), null, persistedNow);
    String route = routeKey(row);
    if (route != null) {
      executionTargetStore.activateOldestEnvironment(route, persistedNow);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  /**
   * Acquire the lock chain Thread -> ToolInvocation -> target and verify the caller still owns the
   * invocation. Returns {@code null} when ownership has been lost (token/attempt/epoch/lease drift,
   * thread mismatch). Throws {@link IllegalStateException} when the invocation is owned but its
   * target row is missing, so the transaction rolls back rather than silently persisting.
   */
  private LockedTool lockOwned(ClaimedToolInvocation claimed, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    ToolInvocation invocation = claimed.invocation();
    HarnessModelInvocationThreadDO thread = threadMapper.findForUpdate(invocation.threadId());
    if (thread == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocation.id());
    }
    if (!Objects.equals(thread.getExecutionEpoch(), invocation.executionEpoch())) {
      return null;
    }
    ToolInvocationDO row = invocationMapper.findForUpdate(invocation.id(), invocation.threadId());
    if (row == null
        || !Objects.equals(row.getExecutionEpoch(), invocation.executionEpoch())
        || !Objects.equals(row.getAttempt(), invocation.attempt())
        || !InvocationStatus.RUNNING.name().equals(row.getStatus())
        || !Objects.equals(row.getWorkerToken(), invocation.workerLease().token())
        || !row.getWorkerUntil().toInstant().isAfter(persistence(now))) {
      return null;
    }
    if (executionTargetStore.lock(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()).isEmpty()) {
      throw new IllegalStateException("owned invocation is missing its target: " + invocation.id());
    }
    return new LockedTool(row);
  }

  private static String routeKey(ToolInvocationDO row) {
    return "ENVIRONMENT".equals(row.getLocation()) ? row.getEnvironmentName() : null;
  }

  private static ToolInvocationUpdateOutcome outcome(int affected) {
    return affected == 1
        ? ToolInvocationUpdateOutcome.APPLIED
        : ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
  }

  private static void requireTargetAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }

  private static OffsetDateTime offset(Instant value) {
    return ToolInvocationRowConverter.offset(value);
  }

  private static Instant persistence(Instant value) {
    return ToolInvocationRowConverter.persistenceInstant(value);
  }

  private static OffsetDateTime advance(Instant base, Duration duration, String name) {
    Instant target = persistence(base.plus(duration));
    if (!target.isAfter(persistence(base))) {
      throw new IllegalArgumentException(name + " must advance PostgreSQL time by at least 1ms");
    }
    return offset(target);
  }

  private static Instant max(Instant left, Instant right) {
    return left.isAfter(right) ? left : right;
  }

  private static void requireToken(String token) {
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new IllegalArgumentException("workerToken must be non-blank and <= 128 chars");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least 1ms");
    }
  }

  private record LockedTool(ToolInvocationDO row) {}
}
