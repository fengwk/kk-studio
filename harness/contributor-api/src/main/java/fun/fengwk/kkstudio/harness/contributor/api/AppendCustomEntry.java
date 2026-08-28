package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.util.Objects;

/** 追加一条 CUSTOM Entry 的声明式意图。 */
public record AppendCustomEntry(CustomEntryPayload payload) {

  public AppendCustomEntry {
    payload = Objects.requireNonNull(payload, "payload");
  }
}
