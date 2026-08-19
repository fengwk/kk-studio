package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个已经失败并被调度重试的 Model attempt 的不可变审计事实。
 *
 * <p>failed attempts 只记录复用 {@link fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy}
 * 的 可重试错误（{@link ProviderErrorKind#TRANSIENT} 与 {@link
 * ProviderErrorKind#INVALID_RESPONSE}）；它不是对话历史， 也不参与任何 Provider request。
 */
public record ModelAttemptFailure(
    int attempt,
    long sequence,
    String text,
    String thinking,
    ModelInvocationError error,
    Instant failedAt,
    Instant retryAt) {

  public ModelAttemptFailure {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    text = Objects.requireNonNull(text, "text");
    thinking = Objects.requireNonNull(thinking, "thinking");
    error = Objects.requireNonNull(error, "error");
    if (error.kind() != ProviderErrorKind.TRANSIENT
        && error.kind() != ProviderErrorKind.INVALID_RESPONSE) {
      throw new IllegalArgumentException("failed attempt requires a retryable error");
    }
    failedAt = requireMillisecondPrecision(failedAt, "failedAt");
    retryAt = requireMillisecondPrecision(retryAt, "retryAt");
    if (retryAt.isBefore(failedAt)) {
      throw new IllegalArgumentException("retryAt must not precede failedAt");
    }
  }

  private static Instant requireMillisecondPrecision(Instant value, String name) {
    Objects.requireNonNull(value, name);
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException(name + " must use millisecond precision");
    }
    return value;
  }
}
