package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/**
 * SET_AGENT 应用后写入的 Agent 配置变更 Entry。
 *
 * <p>只记录 agentDefinitionId 与捕获时的 agentName；不得写入 prompt/tools/skills 等完整配置。 Thread 的 agent 字段是该
 * Thread 当前生效配置。
 */
public record AgentChangeEntryPayload(long agentDefinitionId, String agentName)
    implements SessionEntryPayload {
  public AgentChangeEntryPayload {
    if (agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive");
    }
    agentName = Objects.requireNonNull(agentName, "agentName").trim();
    if (agentName.isEmpty()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.AGENT_CHANGE;
  }
}
