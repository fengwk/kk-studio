package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * 已展示给用户的当前 Model attempt partial 快照。
 *
 * <p>空 text 与空 thinking 表示该 attempt 尚未产生可展示内容；两者始终为非 null 字符串。
 */
public record ModelAttemptSnapshot(int attempt, long sequence, String text, String thinking) {

  public ModelAttemptSnapshot {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    text = Objects.requireNonNull(text, "text");
    thinking = Objects.requireNonNull(thinking, "thinking");
  }
}
