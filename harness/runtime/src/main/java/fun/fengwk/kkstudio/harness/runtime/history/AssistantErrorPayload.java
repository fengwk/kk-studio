package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * Assistant-side 失败审计 Entry。
 *
 * <p>最小快照：仅 {@link AssistantError}（code + message）。UI / audit only；不投影到 Provider Context。 retry
 * 生命周期属于对应的 ModelInvocation。
 */
public record AssistantErrorPayload(AssistantError error) implements EntryPayload {

  public AssistantErrorPayload {
    error = Objects.requireNonNull(error, "error");
  }

  @Override
  public EntryType type() {
    return EntryType.ASSISTANT_ERROR;
  }
}
