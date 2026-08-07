package fun.fengwk.kkstudio.harness.runtime;

/**
 * 不可变的 Stop 请求。
 *
 * <p>{@code stopRequestId} 是客户端生成的幂等键。Runtime 在将其持久化到 TURN_END 之前会按 operation 与 Thread
 * 限定其作用域，因此同一外部 id 在两个 Thread 上不会发生别名冲突。
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
