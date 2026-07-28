package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable durable aggregate mirroring the {@code harness_model_invocation} row in the
 * authoritative {@code schema-postgresql.sql} DDL. The aggregate enforces every lifecycle,
 * time-order, lease, payload and {@code appliedAt} invariant declared by the schema at the
 * constructor boundary so callers cannot construct an invalid state.
 *
 * <p>The lifecycle {@link InvocationStatus} and worker {@link Lease} are imported directly from the
 * shared runtime execution types so this slice does not redefine them. {@code sessionId} is
 * intentionally absent: it is a persistence-side composite-FK carrier and has no meaning in the
 * Runtime aggregate (see {@code fk_harness_model_invocation_thread} / {@code
 * fk_harness_model_invocation_head} in the schema).
 *
 * <p>State transitions are not implemented here; the record exposes only pure query helpers ({@link
 * #isTerminalUnapplied()}, {@link #hasActiveWorkerAt(Instant)}, {@link
 * #isDispatchableAt(Instant)}). All worker mutation belongs to {@link
 * fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions}.
 */
public record ModelInvocation(
    long id,
    long threadId,
    long sourceHeadEntryId,
    long executionEpoch,
    ProviderRequest request,
    InvocationStatus status,
    int attempt,
    Instant nextAttemptAt,
    Lease workerLease,
    Instant deadlineAt,
    Instant lastActivityAt,
    ProviderResponse result,
    ModelInvocationError error,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    SafeStreamSnapshot safeStreamSnapshot) {

  public ModelInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (sourceHeadEntryId <= 0) {
      throw new IllegalArgumentException("sourceHeadEntryId must be positive");
    }
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must be non-negative");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (status == InvocationStatus.QUEUED && safeStreamSnapshot != null) {
      throw new IllegalArgumentException("QUEUED must not carry safe stream snapshot");
    }
    validateShape(
        status,
        nextAttemptAt,
        workerLease,
        deadlineAt,
        lastActivityAt,
        result,
        error,
        appliedAt,
        createdAt,
        startedAt,
        finishedAt);
  }

  /**
   * Returns {@code true} when this invocation reached a terminal status and has not been applied
   * yet. Mirrors the {@code idx_harness_model_invocation_terminal_unapplied} index predicate and
   * reuses {@link InvocationStatus#isTerminal()}.
   */
  public boolean isTerminalUnapplied() {
    return status.isTerminal() && appliedAt == null;
  }

  /**
   * Returns {@code true} when this invocation is currently {@link InvocationStatus#RUNNING RUNNING}
   * with an active lease at {@code now}, delegating to {@link Lease#isActiveAt(Instant)}. {@link
   * InvocationStatus#RETRY_WAIT RETRY_WAIT} invocations never have an active lease by construction.
   */
  public boolean hasActiveWorkerAt(Instant now) {
    Objects.requireNonNull(now, "now");
    return status == InvocationStatus.RUNNING && workerLease.isActiveAt(now);
  }

  /**
   * Returns {@code true} when this invocation is eligible to be picked up by the worker queue at
   * {@code now}: a {@link InvocationStatus#QUEUED QUEUED} record or a {@link
   * InvocationStatus#RETRY_WAIT RETRY_WAIT} record whose {@code nextAttemptAt} has been reached.
   */
  public boolean isDispatchableAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (status == InvocationStatus.QUEUED) {
      return true;
    }
    if (status == InvocationStatus.RETRY_WAIT) {
      return !nextAttemptAt.isAfter(now);
    }
    return false;
  }

  private static void validateShape(
      InvocationStatus status,
      Instant nextAttemptAt,
      Lease workerLease,
      Instant deadlineAt,
      Instant lastActivityAt,
      ProviderResponse result,
      ModelInvocationError error,
      Instant appliedAt,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {
    // 1) Generic time-order invariants from ck_harness_model_invocation_time_order.
    if (startedAt != null && startedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("startedAt must be >= createdAt");
    }
    if (deadlineAt != null && startedAt == null) {
      throw new IllegalArgumentException("deadlineAt requires startedAt");
    }
    if (deadlineAt != null && !deadlineAt.isAfter(startedAt)) {
      throw new IllegalArgumentException("deadlineAt must be strictly after startedAt");
    }
    if (workerLease != null) {
      if (startedAt == null) {
        throw new IllegalArgumentException("workerLease requires startedAt");
      }
      if (!workerLease.until().isAfter(startedAt)) {
        throw new IllegalArgumentException("workerLease.until must be strictly after startedAt");
      }
    }
    if (lastActivityAt != null && startedAt == null) {
      throw new IllegalArgumentException("lastActivityAt requires startedAt");
    }
    if (lastActivityAt != null && lastActivityAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("lastActivityAt must be >= startedAt");
    }
    if (finishedAt != null) {
      Instant floor = startedAt != null ? startedAt : createdAt;
      if (finishedAt.isBefore(floor)) {
        throw new IllegalArgumentException("finishedAt must be >= startedAt or createdAt");
      }
      if (lastActivityAt != null && lastActivityAt.isAfter(finishedAt)) {
        throw new IllegalArgumentException("lastActivityAt must be <= finishedAt");
      }
    }
    if (nextAttemptAt != null) {
      if (deadlineAt == null || lastActivityAt == null) {
        throw new IllegalArgumentException(
            "nextAttemptAt requires both deadlineAt and lastActivityAt");
      }
      if (!nextAttemptAt.isAfter(lastActivityAt)) {
        throw new IllegalArgumentException("nextAttemptAt must be strictly after lastActivityAt");
      }
      if (!nextAttemptAt.isBefore(deadlineAt)) {
        throw new IllegalArgumentException("nextAttemptAt must be strictly before deadlineAt");
      }
    }

    // 2) ck_harness_model_invocation_applied.
    if (appliedAt != null) {
      if (!status.isTerminal()) {
        throw new IllegalArgumentException("appliedAt requires a terminal status");
      }
      if (finishedAt == null) {
        throw new IllegalArgumentException("appliedAt requires finishedAt");
      }
      if (appliedAt.isBefore(finishedAt)) {
        throw new IllegalArgumentException("appliedAt must be >= finishedAt");
      }
    }

    // 3) Status-specific payload and lease shape constraints.
    switch (status) {
      case QUEUED -> {
        requireNull("QUEUED", "nextAttemptAt", nextAttemptAt);
        requireNull("QUEUED", "workerLease", workerLease);
        requireNull("QUEUED", "startedAt", startedAt);
        requireNull("QUEUED", "deadlineAt", deadlineAt);
        requireNull("QUEUED", "lastActivityAt", lastActivityAt);
        requireNull("QUEUED", "finishedAt", finishedAt);
        requireNull("QUEUED", "result", result);
        requireNull("QUEUED", "error", error);
      }
      case RUNNING -> {
        requireNull("RUNNING", "nextAttemptAt", nextAttemptAt);
        requireNull("RUNNING", "finishedAt", finishedAt);
        requireNull("RUNNING", "result", result);
        requireNull("RUNNING", "error", error);
        if (workerLease == null) {
          throw new IllegalArgumentException("RUNNING requires workerLease");
        }
        if (deadlineAt == null || lastActivityAt == null) {
          throw new IllegalArgumentException(
              "RUNNING requires startedAt, deadlineAt and lastActivityAt");
        }
      }
      case RETRY_WAIT -> {
        requireNull("RETRY_WAIT", "workerLease", workerLease);
        requireNull("RETRY_WAIT", "finishedAt", finishedAt);
        requireNull("RETRY_WAIT", "result", result);
        requireNull("RETRY_WAIT", "error", error);
        if (nextAttemptAt == null) {
          throw new IllegalArgumentException("RETRY_WAIT requires nextAttemptAt");
        }
      }
      case SUCCEEDED -> {
        requireNull("SUCCEEDED", "nextAttemptAt", nextAttemptAt);
        requireNull("SUCCEEDED", "workerLease", workerLease);
        requireTerminalFinished(finishedAt);
        requireNull("SUCCEEDED", "error", error);
        if (result == null) {
          throw new IllegalArgumentException("SUCCEEDED requires result");
        }
        if (deadlineAt == null || lastActivityAt == null) {
          throw new IllegalArgumentException(
              "SUCCEEDED requires startedAt, deadlineAt and lastActivityAt");
        }
      }
      case FAILED -> {
        requireNull("FAILED", "nextAttemptAt", nextAttemptAt);
        requireNull("FAILED", "workerLease", workerLease);
        requireTerminalFinished(finishedAt);
        requireNull("FAILED", "result", result);
        if (error == null) {
          throw new IllegalArgumentException("FAILED requires error");
        }
        if (deadlineAt == null || lastActivityAt == null) {
          throw new IllegalArgumentException(
              "FAILED requires startedAt, deadlineAt and lastActivityAt");
        }
      }
      case UNKNOWN -> {
        requireNull("UNKNOWN", "nextAttemptAt", nextAttemptAt);
        requireNull("UNKNOWN", "workerLease", workerLease);
        requireTerminalFinished(finishedAt);
        requireNull("UNKNOWN", "result", result);
        if (error == null) {
          throw new IllegalArgumentException("UNKNOWN requires error");
        }
        if (deadlineAt == null || lastActivityAt == null) {
          throw new IllegalArgumentException(
              "UNKNOWN requires startedAt, deadlineAt and lastActivityAt");
        }
      }
      case CANCELLED -> {
        requireNull("CANCELLED", "nextAttemptAt", nextAttemptAt);
        requireNull("CANCELLED", "workerLease", workerLease);
        requireTerminalFinished(finishedAt);
        requireNull("CANCELLED", "result", result);
        requireNull("CANCELLED", "error", error);
        boolean emptyClocks = startedAt == null && deadlineAt == null && lastActivityAt == null;
        boolean fullClocks = startedAt != null && deadlineAt != null && lastActivityAt != null;
        if (!emptyClocks && !fullClocks) {
          throw new IllegalArgumentException(
              "CANCELLED requires either no execution clocks or the full running triad");
        }
      }
    }
  }

  private static void requireNull(String status, String name, Object value) {
    if (value != null) {
      throw new IllegalArgumentException(status + " must have null " + name);
    }
  }

  private static void requireTerminalFinished(Instant finishedAt) {
    if (finishedAt == null) {
      throw new IllegalArgumentException("terminal status requires finishedAt");
    }
  }
}
