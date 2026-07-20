package fun.fengwk.kkstudio.harness.runtime.context;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Turn 生效的 Agent 运行时配置。
 *
 * <p>由 Thread 当前状态（agent/model/variant/yolo）与当前 AgentDefinition 动态装载的
 * prompt/tools/skills/subagents/policy/environment 组合而成，不从 Session Entry path fold。
 */
public record AgentRuntimeConfig(
    Long agentDefinitionId,
    String systemPrompt,
    String modelId,
    String variant,
    String environmentName,
    List<String> tools,
    List<String> skills,
    List<SelectedSkillMetadata> selectedSkills,
    List<String> allowedSubagents,
    String executionPolicyJson,
    boolean yoloEnabled) {
  public AgentRuntimeConfig {
    if (agentDefinitionId != null && agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive when present");
    }
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank when present");
    }
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    selectedSkills = List.copyOf(Objects.requireNonNull(selectedSkills, "selectedSkills"));
    allowedSubagents = List.copyOf(Objects.requireNonNull(allowedSubagents, "allowedSubagents"));
  }

  public AgentRuntimeConfig withModel(String modelId, String variant) {
    return new AgentRuntimeConfig(
        agentDefinitionId,
        systemPrompt,
        modelId,
        variant,
        environmentName,
        tools,
        skills,
        selectedSkills,
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
        environmentName,
        tools,
        skills,
        selectedSkills,
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
        environmentName,
        tools,
        skills,
        selectedSkills,
        allowedSubagents,
        executionPolicyJson,
        yoloEnabled);
  }
}
