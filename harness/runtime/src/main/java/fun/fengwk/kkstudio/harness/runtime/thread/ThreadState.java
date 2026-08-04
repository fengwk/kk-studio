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
 *
 * <p>All state changes go through the pure transition methods below; every externally visible
 * change bumps {@code revision} by exactly one. The Store must run {@link #validateTransition}
 * before every {@code updateThread} write so direct record construction stays limited to
 * persistence decode.
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

  /**
   * Validates that {@code next} is a legal transition of the stored {@code stored} row: identity is
   * immutable, {@code nextCommandSequence} / {@code revision} / {@code updatedAt} never regress,
   * and any externally visible change bumps {@code revision} by exactly one. Exact replay is always
   * accepted.
   */
  public static void validateTransition(ThreadState stored, ThreadState next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    if (stored.id() != next.id()) {
      throw new IllegalArgumentException("thread id must not change");
    }
    if (!stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException("thread createdAt must not change");
    }
    if (next.nextCommandSequence() < stored.nextCommandSequence()) {
      throw new IllegalArgumentException("nextCommandSequence must not regress");
    }
    if (next.updatedAt().isBefore(stored.updatedAt())) {
      throw new IllegalArgumentException("updatedAt must not regress");
    }
    if (next.revision() != Math.addExact(stored.revision(), 1L)) {
      throw new IllegalArgumentException(
          "any thread state change must bump revision by exactly one");
    }
  }

  /**
   * Reserves {@code count} Command sequences in one atomic step: {@code nextCommandSequence}
   * advances by {@code count} and {@code revision} by exactly one; {@code count} must be positive.
   */
  public ThreadState reserveCommandSequences(int count, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            Math.addExact(nextCommandSequence, (long) count),
            Math.addExact(revision, 1L),
            createdAt,
            requireNow(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * Advances the head Entry cursor and sets the frozen YOLO runtime policy in one atomic step
   * (terminal apply re-sends the current policy value); {@code revision} advances by exactly one.
   */
  public ThreadState advanceHead(long headEntryId, boolean yoloEnabled, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            requireNow(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * Explicitly bumps the externally visible snapshot revision without changing other durable
   * fields.
   */
  public ThreadState touchRevision(Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            requireNow(now));
    validateTransition(this, next);
    return next;
  }

  private static Instant requireNow(Instant now) {
    return Objects.requireNonNull(now, "now");
  }
}
