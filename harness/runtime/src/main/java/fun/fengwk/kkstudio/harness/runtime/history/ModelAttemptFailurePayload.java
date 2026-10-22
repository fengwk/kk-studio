package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.time.Instant;
import java.util.Objects;

/** Model retry failure 的透明历史审计节点；不属于对话语义，也不投影给 Provider。 */
public record ModelAttemptFailurePayload(
    ModelAttemptSnapshot attempt,
    ModelSelection model,
    Instant requestedAt,
    AssistantError error,
    Instant retryAt)
    implements EntryPayload {

  public ModelAttemptFailurePayload {
    attempt = Objects.requireNonNull(attempt, "attempt");
    model = Objects.requireNonNull(model, "model");
    error = Objects.requireNonNull(error, "error");
    if (requestedAt != null) {
      requestedAt = requireMillisecondPrecision(requestedAt);
    }
    retryAt = requireMillisecondPrecision(retryAt);
  }

  @Override
  public EntryType type() {
    return EntryType.MODEL_ATTEMPT_FAILURE;
  }

  private static Instant requireMillisecondPrecision(Instant value) {
    Objects.requireNonNull(value, "retryAt");
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("retryAt must use millisecond precision");
    }
    return value;
  }
}
