package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/**
 * Safely recoverable streaming text/thinking checkpoint of the current attempt.
 *
 * <p>{@code attempt} and {@code sequence} prevent partial content of different retry attempts from
 * mixing; {@code text}/{@code thinking} are nullable but at least one must carry content. ToolCall
 * fragments never enter this type; a new attempt starts with an empty checkpoint.
 */
public record StreamCheckpoint(int attempt, long sequence, String text, String thinking) {

  public StreamCheckpoint {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    if (isBlank(text) && isBlank(thinking)) {
      throw new IllegalArgumentException("text and thinking must not both be empty");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
