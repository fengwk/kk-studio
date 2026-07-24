package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.model.worker.HarnessModelInvocationThreadDO;
import fun.fengwk.kkstudio.core.harness.model.worker.HarnessModelInvocationThreadMapper;
import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Final PostgreSQL ToolInvocation worker transaction adapter. */
@Service
public class PostgresqlToolInvocationTransactions implements ToolInvocationTransactions {

  private static final int MAX_TOKEN_LENGTH = 128;

  private final PostgresqlToolInvocationMapper invocationMapper;
  private final HarnessModelInvocationThreadMapper threadMapper;

  public PostgresqlToolInvocationTransactions(
      PostgresqlToolInvocationMapper invocationMapper,
      HarnessModelInvocationThreadMapper threadMapper) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ToolInvocation> findClaimable(long invocationId, Instant now) {
    requirePositive(invocationId, "invocationId");
    ToolInvocationDO row = invocationMapper.findClaimable(invocationId, offset(now));
    return row == null
        ? Optional.empty()
        : Optional.of(ToolInvocationRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ToolInvocation> findNextClaimable(
      ToolExecutionLocation location, String environmentName, Instant now) {
    Objects.requireNonNull(location, "location");
    if (location == ToolExecutionLocation.PLATFORM && environmentName != null) {
      throw new IllegalArgumentException("PLATFORM scan must not specify environmentName");
    }
    if (location == ToolExecutionLocation.ENVIRONMENT
        && (environmentName == null || environmentName.isBlank())) {
      throw new IllegalArgumentException("ENVIRONMENT scan requires environmentName");
    }
    ToolInvocationDO row =
        invocationMapper.findNextClaimable(location.name(), environmentName, offset(now));
    return row == null
        ? Optional.empty()
        : Optional.of(ToolInvocationRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ToolInvocation> findNextExpiredRunning(
      ToolExecutionLocation location, Instant now) {
    Objects.requireNonNull(location, "location");
    ToolInvocationDO row = invocationMapper.findNextExpiredRunning(location.name(), offset(now));
    return row == null
        ? Optional.empty()
        : Optional.of(ToolInvocationRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ClaimedToolInvocation> claim(
      long invocationId,
      String workerToken,
      Duration executionTimeout,
      Duration workerLeaseDuration,
      Instant now) {
    requirePositive(invocationId, "invocationId");
    requireToken(workerToken);
    requirePositive(executionTimeout, "executionTimeout");
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    Instant persistedNow = persistence(now);
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
    ToolInvocationDO row = invocationMapper.findForUpdate(invocationId, peek.getThreadId());
    if (row == null || !Objects.equals(row.getExecutionEpoch(), thread.getExecutionEpoch())) {
      return Optional.empty();
    }
    OffsetDateTime nowOffset = offset(persistedNow);
    OffsetDateTime leaseUntil = advance(persistedNow, workerLeaseDuration, "workerLeaseDuration");
    InvocationStatus status = InvocationStatus.valueOf(row.getStatus());
    int affected;
    boolean recovered = false;
    if (status == InvocationStatus.QUEUED) {
      Instant startedAt = max(persistedNow, row.getCreatedAt().toInstant());
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
    } else if (status == InvocationStatus.RUNNING) {
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
    } else {
      return Optional.empty();
    }
    if (affected != 1) {
      return Optional.empty();
    }
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
    return Optional.of(new ClaimedToolInvocation(aggregate, recovered));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome renew(
      ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    ToolInvocationDO row = lockOwned(claimed, now);
    if (row == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    int affected =
        invocationMapper.renew(
            row.getId(),
            row.getThreadId(),
            row.getExecutionEpoch(),
            row.getAttempt(),
            claimed.invocation().workerLease().token(),
            advance(
                max(persistence(now), row.getStartedAt().toInstant()),
                workerLeaseDuration,
                "workerLeaseDuration"),
            offset(now));
    return outcome(affected);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome recordActivity(
      ClaimedToolInvocation claimed, Instant activityAt, Instant now) {
    if (lockOwned(claimed, now) == null) {
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
            offset(now)));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome releaseUnstarted(
      ClaimedToolInvocation claimed,
      InvocationStatus previousStatus,
      Instant nextAttemptAt,
      Instant now) {
    Objects.requireNonNull(previousStatus, "previousStatus");
    ToolInvocationDO row = lockOwned(claimed, now);
    if (row == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocation invocation = claimed.invocation();
    int affected;
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
              offset(now));
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
              offset(now));
    } else {
      throw new IllegalArgumentException("previousStatus must be QUEUED or RETRY_WAIT");
    }
    return outcome(affected);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeSuccess(
      ClaimedToolInvocation claimed,
      Supplier<ToolResult> resultSupplier,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(resultSupplier, "resultSupplier");
    ToolInvocationDO row = lockOwned(claimed, now);
    if (row == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolResult result =
        Objects.requireNonNull(resultSupplier.get(), "resultSupplier returned null");
    if (!claimed.invocation().toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("result.toolCallId must match the claimed invocation");
    }
    return applyTerminal(
        claimed, row, result, null, lastObservedActivityAt, now, InvocationStatus.SUCCEEDED);
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
    ToolInvocationDO row = lockOwned(claimed, now);
    if (row == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    Instant effectiveActivity =
        max(row.getLastActivityAt().toInstant(), persistence(lastObservedActivityAt));
    Instant next = persistence(nextAttemptAt);
    if (!next.isAfter(effectiveActivity) || !next.isBefore(row.getDeadlineAt().toInstant())) {
      throw new IllegalArgumentException(
          "nextAttemptAt must be after effective activity and before deadline");
    }
    ToolInvocation invocation = claimed.invocation();
    return outcome(
        invocationMapper.scheduleRetry(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            offset(lastObservedActivityAt),
            offset(next),
            offset(now)));
  }

  private ToolInvocationUpdateOutcome complete(
      ClaimedToolInvocation claimed,
      ToolResult result,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      InvocationStatus terminalStatus) {
    ToolInvocationDO row = lockOwned(claimed, now);
    if (row == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    return applyTerminal(claimed, row, result, error, lastObservedActivityAt, now, terminalStatus);
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
    Instant finished = max(persistence(now), max(observed, row.getLastActivityAt().toInstant()));
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
              offset(now));
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
              offset(now));
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
              offset(now));
    }
    if (affected != 1) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    if (threadMapper.markRunnable(
            invocation.threadId(), invocation.executionEpoch(), offset(finished))
        != 1) {
      throw new IllegalStateException("cannot mark owning thread runnable");
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  private ToolInvocationDO lockOwned(ClaimedToolInvocation claimed, Instant now) {
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
    return row;
  }

  private static ToolInvocationUpdateOutcome outcome(int affected) {
    return affected == 1
        ? ToolInvocationUpdateOutcome.APPLIED
        : ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
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
}
