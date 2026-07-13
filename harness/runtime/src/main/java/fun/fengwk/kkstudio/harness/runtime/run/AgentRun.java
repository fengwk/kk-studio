package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Instant;
import java.util.Objects;

/** 数据库可恢复的不可变 Agent Run 快照。 */
public record AgentRun(
    long id,
    long sessionId,
    long triggerEntryId,
    RunStatus status,
    int turnIndex,
    int attempt,
    long eventSequence,
    String leaseOwner,
    Instant leaseUntil,
    Instant nextAttemptAt,
    Instant cancelRequestedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant updatedAt) {

  public AgentRun {
    if (id <= 0 || sessionId <= 0 || triggerEntryId <= 0) {
      throw new IllegalArgumentException("run, session and trigger entry ids must be positive");
    }
    status = Objects.requireNonNull(status, "status");
    if (turnIndex < 0 || attempt < 0 || eventSequence < 0) {
      throw new IllegalArgumentException("run counters must not be negative");
    }
    if ((leaseOwner == null) != (leaseUntil == null)) {
      throw new IllegalArgumentException("lease owner and lease until must be set together");
    }
    if (status != RunStatus.RUNNING && leaseOwner != null) {
      throw new IllegalArgumentException("only running runs may hold a lease");
    }
    if (status.terminal() != (finishedAt != null)) {
      throw new IllegalArgumentException("terminal run must have exactly one finished timestamp");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
  }

  public boolean isOwnedBy(String owner, int claimedAttempt) {
    return status == RunStatus.RUNNING
        && Objects.equals(leaseOwner, owner)
        && attempt == claimedAttempt;
  }
}
