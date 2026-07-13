package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** Append 时由调用方提供的 Entry 语义内容；父节点由当前 leaf 决定。 */
public record SessionEntryDraft(Long runId, SessionEntryPayload payload) {
  public SessionEntryDraft {
    if (runId != null && runId <= 0) {
      throw new IllegalArgumentException("runId must be positive when present");
    }
    payload = Objects.requireNonNull(payload, "payload");
  }

  public SessionEntryDraft(SessionEntryPayload payload) {
    this(null, payload);
  }
}
