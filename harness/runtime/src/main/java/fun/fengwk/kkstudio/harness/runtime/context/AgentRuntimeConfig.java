package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;

import java.util.List;
import java.util.Objects;

/** Context 路径末端生效的 Agent 配置；含派生 agent id 与 YOLO。 */
public record AgentRuntimeConfig(
    Long agentDefinitionId,
    String systemPrompt,
    String modelId,
    String variant,
    List<String> tools,
    List<String> skills,
    List<String> allowedSubagents,
    String executionPolicyJson,
    boolean yoloEnabled) {
  public AgentRuntimeConfig {
    if (agentDefinitionId != null && agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive when present");
    }
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    allowedSubagents = List.copyOf(Objects.requireNonNull(allowedSubagents, "allowedSubagents"));
  }

  public static AgentRuntimeConfig from(Long agentDefinitionId, AgentSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return new AgentRuntimeConfig(
        agentDefinitionId,
        snapshot.systemPrompt(),
        snapshot.modelId(),
        snapshot.variant(),
        snapshot.tools(),
        snapshot.skills(),
        snapshot.allowedSubagents(),
        snapshot.executionPolicyJson(),
        false);
  }

  public static AgentRuntimeConfig from(AgentSnapshot snapshot) {
    return from(null, snapshot);
  }

  public AgentRuntimeConfig withModel(String modelId, String variant) {
    return new AgentRuntimeConfig(
        agentDefinitionId,
        systemPrompt,
        modelId,
        variant,
        tools,
        skills,
        allowedSubagents,
        executionPolicyJson,
        yoloEnabled);
  }

  public AgentRuntimeConfig withTools(List<String> tools) {
    return new AgentRuntimeConfig(
        agentDefinitionId,
        systemPrompt,
        modelId,
        variant,
        tools,
        skills,
        allowedSubagents,
        executionPolicyJson,
        yoloEnabled);
  }

  public AgentRuntimeConfig withYolo(boolean yoloEnabled) {
    return new AgentRuntimeConfig(
        agentDefinitionId,
        systemPrompt,
        modelId,
        variant,
        tools,
        skills,
        allowedSubagents,
        executionPolicyJson,
        yoloEnabled);
  }

  public AgentRuntimeConfig withAgentDefinitionId(Long agentDefinitionId) {
    return new AgentRuntimeConfig(
        agentDefinitionId,
        systemPrompt,
        modelId,
        variant,
        tools,
        skills,
        allowedSubagents,
        executionPolicyJson,
        yoloEnabled);
  }
}
