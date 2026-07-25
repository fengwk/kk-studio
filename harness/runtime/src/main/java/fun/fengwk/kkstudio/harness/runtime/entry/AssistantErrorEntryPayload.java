package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;

import java.util.Objects;

/**
 * Assistant-side 失败审计 Entry。最小快照：仅 {@link ModelInvocationError}（kind + message）。UI / audit
 * only；不投影到 Provider Context。retry 生命周期属于对应的 ModelInvocation。
 */
public record AssistantErrorEntryPayload(ModelInvocationError error)
    implements RuntimeEntryPayload {

  public AssistantErrorEntryPayload {
    Objects.requireNonNull(error, "error");
  }

  @Override
  public EntryType type() {
    return EntryType.ASSISTANT_ERROR;
  }
}
