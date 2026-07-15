package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import java.time.Instant;
import java.util.Objects;

/** Cursorable root-tree activity projection. Event id is the global Snowflake cursor. */
public record RootActivity(
    long rootSessionId,
    long sessionId,
    long runId,
    long eventId,
    long sequence,
    RunEventType type,
    String payloadJson,
    Instant createdAt) {
  public RootActivity {
    if (rootSessionId <= 0 || sessionId <= 0 || runId <= 0 || eventId <= 0) {
      throw new IllegalArgumentException("root activity ids must be positive");
    }
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    type = Objects.requireNonNull(type, "type");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
