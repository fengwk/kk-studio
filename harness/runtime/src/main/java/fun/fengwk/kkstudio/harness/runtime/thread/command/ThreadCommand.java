package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable durable Thread mailbox command aggregate.
 *
 * <p>Lifecycle state is derived from the two nullable terminal markers. No status or appliedAt
 * projection is stored.
 */
public record ThreadCommand(
    long id,
    long threadId,
    long sequence,
    ThreadCommandPayload payload,
    String clientCommandId,
    Long consumedTurnStartEntryId,
    Instant cancelledAt,
    Instant createdAt) {

  public ThreadCommand {
    if (id <= 0) {
      throw new IllegalArgumentException("command id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    payload = Objects.requireNonNull(payload, "payload");
    clientCommandId =
        CommandValueValidation.requireCanonicalName(clientCommandId, "clientCommandId");
    if (consumedTurnStartEntryId != null && consumedTurnStartEntryId <= 0) {
      throw new IllegalArgumentException("consumedTurnStartEntryId must be positive");
    }
    if (consumedTurnStartEntryId != null && cancelledAt != null) {
      throw new IllegalArgumentException(
          "consumedTurnStartEntryId and cancelledAt must not both be present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (cancelledAt != null && cancelledAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("cancelledAt must not precede createdAt");
    }
  }

  /** Derives QUEUED/APPLIED/CANCELLED from durable markers. */
  public ThreadCommandState state() {
    if (consumedTurnStartEntryId != null) {
      return ThreadCommandState.APPLIED;
    }
    if (cancelledAt != null) {
      return ThreadCommandState.CANCELLED;
    }
    return ThreadCommandState.QUEUED;
  }

  /** Returns the typed command kind without storing a duplicate discriminator. */
  public ThreadCommandType type() {
    return payload.type();
  }
}
