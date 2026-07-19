package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/**
 * 持久化的 Branch 运行单元。
 *
 * <p>仅持有 head、mailbox sequence、执行状态与 processor lease；Agent/Model/Toolset/YOLO 由 Entry path fold。
 */
public record AgentThread(
    long id,
    long sessionId,
    long headEntryId,
    ThreadStatus status,
    long inputSequence,
    String processorToken,
    Instant processorUntil,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public AgentThread {
    if (id <= 0 || sessionId <= 0 || headEntryId <= 0) {
      throw new IllegalArgumentException("thread/session/head ids must be positive");
    }
    status = Objects.requireNonNull(status, "status");
    if (inputSequence < 0 || version < 0) {
      throw new IllegalArgumentException("inputSequence and version must not be negative");
    }
    if (processorToken != null && processorToken.isBlank()) {
      throw new IllegalArgumentException("processorToken must not be blank when present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public boolean isProcessing(Instant now) {
    return processorToken != null
        && processorUntil != null
        && now != null
        && processorUntil.isAfter(now);
  }
}
