package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * Assistant-side 失败审计 Entry。
 *
 * <p>错误与 Provider attempt 已经被用户看到的 partial 明确分离。该 payload 仅用于 UI / audit，不投影到 Provider Context。
 */
public record AssistantErrorPayload(AssistantError error, ModelAttemptSnapshot attempt)
    implements EntryPayload {

  public AssistantErrorPayload {
    error = Objects.requireNonNull(error, "error");
    // A null attempt is intentional for planning and stop-barrier failures.
  }

  @Override
  public EntryType type() {
    return EntryType.ASSISTANT_ERROR;
  }
}
