package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;

/**
 * Immutable Stop result.
 *
 * <p>{@link Status#IDLE} means no stopped Turn was created, but {@code cancelledCommandCount} may
 * still be positive. {@link Status#REPLAYED} identifies the previously stopped TURN_END while the
 * returned Thread may already point at a newer Turn.
 */
public record StopResult(
    Status status, ThreadState thread, Long stoppedTurnEndEntryId, int cancelledCommandCount) {

  public StopResult {
    status = Objects.requireNonNull(status, "status");
    thread = Objects.requireNonNull(thread, "thread");
    if (cancelledCommandCount < 0) {
      throw new IllegalArgumentException("cancelledCommandCount must not be negative");
    }
    if (status == Status.IDLE) {
      if (stoppedTurnEndEntryId != null) {
        throw new IllegalArgumentException("IDLE must not carry a stopped TURN_END id");
      }
    } else if (stoppedTurnEndEntryId == null || stoppedTurnEndEntryId <= 0) {
      throw new IllegalArgumentException("STOPPED/REPLAYED require a positive stopped TURN_END id");
    }
    if (status == Status.REPLAYED && cancelledCommandCount != 0) {
      throw new IllegalArgumentException("REPLAYED must not cancel commands");
    }
  }

  /** Whether this call stopped a Turn, had no live Turn, or replayed an earlier Stop. */
  public enum Status {
    STOPPED,
    IDLE,
    REPLAYED
  }
}
