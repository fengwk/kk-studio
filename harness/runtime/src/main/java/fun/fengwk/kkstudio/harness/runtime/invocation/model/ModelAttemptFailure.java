package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个已经失败并被调度重试的 Model attempt 的不可变审计事实。
 *
 * <p>failed attempts 记录真实失败 attempt；普通单模型重试仍只接受 {@link
 * ModelInvocationError#retryCandidate()}。模型组切换到下一成员时，确定性 AUTHENTICATION / BILLING 也可作为审计进入， 但仍由
 * Invocation transition 限定只能随组推进。OVERFLOW 与 CANCELLED 永不进入。它不是对话历史，也不参与任何 Provider request。
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
    if (error.kind() == ProviderErrorKind.CANCELLED || error.kind() == ProviderErrorKind.OVERFLOW) {
      throw new IllegalArgumentException(
          "cancelled or overflow attempts are not model fallback audit failures");
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
