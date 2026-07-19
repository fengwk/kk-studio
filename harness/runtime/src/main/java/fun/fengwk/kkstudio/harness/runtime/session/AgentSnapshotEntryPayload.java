package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/**
 * Agent 冻结快照 Entry；同时记录源 {@code agentDefinitionId} 与完整 snapshot。
 *
 * <p>Snapshot 重置 Agent/Model/Toolset/Skill/Subagent allowlist 与 execution policy；YOLO 独立 fold。
 */
public record AgentSnapshotEntryPayload(Long agentDefinitionId, AgentSnapshot snapshot)
    implements SessionEntryPayload {
  public AgentSnapshotEntryPayload {
    if (agentDefinitionId != null && agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive when present");
    }
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.AGENT_SNAPSHOT;
  }
}
