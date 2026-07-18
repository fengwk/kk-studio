package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;

import java.time.Instant;
import java.util.Objects;

/** Cursorable root-tree activity projection. Event id is the global Snowflake cursor. */
public record RootActivity(
    long rootSessionId,
    long sessionId,
    long threadId,
    long eventId,
    ThreadEventType type,
    String payloadJson,
    Instant createdAt) {
  public RootActivity {
    if (rootSessionId <= 0 || sessionId <= 0 || threadId <= 0 || eventId <= 0) {
      throw new IllegalArgumentException("root activity ids must be positive");
    }
    type = Objects.requireNonNull(type, "type");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
