package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/**
 * 当前 attempt 的安全可恢复 streaming text/thinking checkpoint。
 *
 * <p>{@code attempt} 与 {@code sequence} 防止不同 retry attempt 的部分内容相互混杂；{@code text}/ {@code thinking}
 * 始终为非 null 字符串，但至少其中之一必须非空。ToolCall 片段永远不进入此类型；新 attempt 从一个空 checkpoint 开始。
 */
public record StreamCheckpoint(int attempt, long sequence, String text, String thinking) {

  public StreamCheckpoint {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    text = text == null ? "" : text;
    thinking = thinking == null ? "" : thinking;
    if (text.isEmpty() && thinking.isEmpty()) {
      throw new IllegalArgumentException("text and thinking must not both be empty");
    }
  }
}
