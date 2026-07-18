package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** Append 时由调用方提供的 Entry 语义内容；父节点由 Thread head 或显式 parent 决定。 */
public record SessionEntryDraft(SessionEntryPayload payload) {
  public SessionEntryDraft {
    payload = Objects.requireNonNull(payload, "payload");
  }
}
