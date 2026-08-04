package fun.fengwk.kkstudio.harness.runtime;

/**
 * Immutable Stop request.
 *
 * <p>{@code stopRequestId} is the client-generated idempotency key. The Runtime scopes it by
 * operation and Thread before persisting it in a TURN_END, so the same external id on two Threads
 * never aliases.
 */
public record StopCommand(long threadId, String stopRequestId, long expectedRevision) {

  private static final int STOP_REQUEST_ID_MAX_LENGTH = 128;

  public StopCommand {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (stopRequestId == null || stopRequestId.isBlank()) {
      throw new IllegalArgumentException("stopRequestId must not be blank");
    }
    if (!stopRequestId.equals(stopRequestId.strip())) {
      throw new IllegalArgumentException("stopRequestId must not contain surrounding whitespace");
    }
    if (stopRequestId.length() > STOP_REQUEST_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "stopRequestId must be <= " + STOP_REQUEST_ID_MAX_LENGTH + " characters");
    }
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
