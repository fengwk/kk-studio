package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** Append 时由调用方提供的 Entry 语义内容；父节点由当前 leaf 决定。 */
public record SessionEntryDraft(SessionEntryPayload payload) {
  public SessionEntryDraft {
    payload = Objects.requireNonNull(payload, "payload");
  }
}
