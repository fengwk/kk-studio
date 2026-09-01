package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 当前 Thread branch 上可见的一个 Model failed attempt 投影。 */
public record ModelAttemptFailureProjection(
    UUID modelInvocationId,
    UUID turnStartEntryId,
    UUID requestHeadEntryId,
    int attempt,
    long sequence,
    String text,
    String thinking,
    ModelInvocationError error,
    Instant failedAt,
    Instant retryAt) {

  public ModelAttemptFailureProjection {
    Objects.requireNonNull(modelInvocationId, "modelInvocationId");
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    Objects.requireNonNull(requestHeadEntryId, "requestHeadEntryId");
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    text = Objects.requireNonNull(text, "text");
    thinking = Objects.requireNonNull(thinking, "thinking");
    error = Objects.requireNonNull(error, "error");
    failedAt = Objects.requireNonNull(failedAt, "failedAt");
    retryAt = Objects.requireNonNull(retryAt, "retryAt");
    if (retryAt.isBefore(failedAt)) {
      throw new IllegalArgumentException("retryAt must not precede failedAt");
    }
  }
}
