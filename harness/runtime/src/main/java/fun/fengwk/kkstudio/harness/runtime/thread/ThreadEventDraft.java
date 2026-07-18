package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/** 待写入 ThreadEvent journal 的草稿。 */
public record ThreadEventDraft(ThreadEventType type, Long subjectEntryId, String payloadJson) {
  public ThreadEventDraft {
    type = Objects.requireNonNull(type, "type");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    if (subjectEntryId != null && subjectEntryId <= 0) {
      throw new IllegalArgumentException("subjectEntryId must be positive when present");
    }
  }

  public ThreadEventDraft(ThreadEventType type, String payloadJson) {
    this(type, null, payloadJson);
  }
}
