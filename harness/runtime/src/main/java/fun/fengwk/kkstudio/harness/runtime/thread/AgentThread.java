package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/**
 * 持久化的 Branch 运行单元。
 *
 * <p>持有 head、mailbox sequence、执行状态、processor lease、当前生效配置（agent / model / variant）与运行策略（YOLO）。完整
 * system prompt、tools、skills 等不落 Thread 行，Turn 时按当前 AgentDefinition 动态装载。
 */
public record AgentThread(
    long id,
    long sessionId,
    long headEntryId,
    ThreadStatus status,
    long inputSequence,
    int retryAttempt,
    Instant retryAt,
    Long activeAgentDefinitionId,
    String activeAgentName,
    String modelId,
    String variant,
    boolean yoloEnabled,
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
    if (inputSequence < 0 || retryAttempt < 0 || version < 0) {
      throw new IllegalArgumentException(
          "inputSequence, retryAttempt and version must not be negative");
    }
    if (status == ThreadStatus.RETRYING && (retryAttempt <= 0 || retryAt == null)) {
      throw new IllegalArgumentException("retrying thread must have retry attempt and retryAt");
    }
    if (status != ThreadStatus.RETRYING && retryAt != null) {
      throw new IllegalArgumentException("only retrying thread may have retryAt");
    }
    if (activeAgentDefinitionId != null && activeAgentDefinitionId <= 0) {
      throw new IllegalArgumentException("activeAgentDefinitionId must be positive when present");
    }
    if (activeAgentName != null && activeAgentName.isBlank()) {
      throw new IllegalArgumentException("activeAgentName must not be blank when present");
    }
    if (modelId != null && modelId.isBlank()) {
      throw new IllegalArgumentException("modelId must not be blank when present");
    }
    if (variant != null && variant.isBlank()) {
      throw new IllegalArgumentException("variant must not be blank when present");
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
