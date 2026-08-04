package fun.fengwk.kkstudio.harness.runtime;

/**
 * Immutable MOVE_HEAD request: relocate the Thread cursor to {@code targetEntryId} under the exact
 * {@code expectedRevision} optimistic CAS. {@code expectedRevision} must not be negative.
 */
public record MoveHeadCommand(long threadId, long targetEntryId, long expectedRevision) {

  public MoveHeadCommand {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (targetEntryId <= 0) {
      throw new IllegalArgumentException("targetEntryId must be positive");
    }
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
