package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable current Thread state.
 *
 * <p>Only the fields the Thread itself owns durably are stored: the head Entry cursor, the Thread
 * YOLO runtime policy, the next Command sequence and the externally visible snapshot revision.
 * Session, environment, status, open turn, runnable flag, execution epoch and processor lease are
 * deliberately absent; session and environment facts are derived from the Entry branch at {@code
 * headEntryId}.
 */
public record ThreadState(
    long id,
    long headEntryId,
    boolean yoloEnabled,
    long nextCommandSequence,
    long revision,
    Instant createdAt,
    Instant updatedAt) {

  public ThreadState {
    if (id <= 0) {
      throw new IllegalArgumentException("thread id must be positive");
    }
    if (headEntryId <= 0) {
      throw new IllegalArgumentException("headEntryId must be positive");
    }
    if (nextCommandSequence < 1) {
      throw new IllegalArgumentException("nextCommandSequence must start at 1");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }
}
