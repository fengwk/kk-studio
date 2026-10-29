package fun.fengwk.kkstudio.harness.runtime.driver;

import java.time.Instant;
import java.util.Objects;

/**
 * 中立的一次 failed generation attempt 投影。
 *
 * <p>公开生命周期只消费稳定错误码与用户可读消息；Provider 枚举与原始 DTO 由 Driver 私有解释。
 */
public record FailureProjection(
    int attempt,
    long sequence,
    String text,
    String thinking,
    String errorCode,
    String errorMessage,
    Instant failedAt,
    Instant retryAt) {

  public FailureProjection {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    text = Objects.requireNonNull(text, "text");
    thinking = Objects.requireNonNull(thinking, "thinking");
    errorCode = Objects.requireNonNull(errorCode, "errorCode");
    errorMessage = Objects.requireNonNull(errorMessage, "errorMessage");
    failedAt = Objects.requireNonNull(failedAt, "failedAt");
    retryAt = Objects.requireNonNull(retryAt, "retryAt");
  }
}
