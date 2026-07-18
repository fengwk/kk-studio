package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/**
 * 持久化的用户执行面板 / Tree cursor。
 *
 * <p>Branch 由 {@code headEntryId} 路径派生，不独立建模。{@code processorToken}/{@code processorUntil} 实现跨节点单飞。
 */
public record AgentThread(
    long id,
    long sessionId,
    long headEntryId,
    Long agentDefinitionId,
    String runtimeConfigJson,
    boolean yoloEnabled,
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
    if (agentDefinitionId != null && agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive when present");
    }
    if (inputSequence < 0 || version < 0) {
      throw new IllegalArgumentException("inputSequence and version must not be negative");
    }
    runtimeConfigJson = Objects.requireNonNull(runtimeConfigJson, "runtimeConfigJson");
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
