package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/**
 * 当前 attempt 的安全可恢复 streaming text/thinking checkpoint。
 *
 * <p>{@code attempt} 与 {@code sequence} 防止不同 retry attempt 的部分内容相互混杂；{@code text}/ {@code thinking}
 * 可空，但至少其中之一必须携带内容。ToolCall 片段永远不进入此类型；新 attempt 从一个空 checkpoint 开始。
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
