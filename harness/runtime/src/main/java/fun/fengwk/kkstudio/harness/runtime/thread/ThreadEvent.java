package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/** Thread 事件 journal 行；全局递增 id 作为 SSE cursor。 */
public record ThreadEvent(
    long id,
    long threadId,
    Long subjectEntryId,
    ThreadEventType eventType,
    String payloadJson,
    Instant createdAt) {

  public ThreadEvent {
    if (id <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("event and thread ids must be positive");
    }
    if (subjectEntryId != null && subjectEntryId <= 0) {
      throw new IllegalArgumentException("subjectEntryId must be positive when present");
    }
    eventType = Objects.requireNonNull(eventType, "eventType");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
