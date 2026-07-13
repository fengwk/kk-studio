package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import java.util.List;

/** Context 路径末端生效的 Agent 配置。 */
public record AgentRuntimeConfig(
    String systemPrompt, String modelId, String variant, List<String> tools, List<String> skills,
    List<String> allowedSubagents, String executionPolicyJson) {
  public AgentRuntimeConfig {
    tools = List.copyOf(tools);
    skills = List.copyOf(skills);
    allowedSubagents = List.copyOf(allowedSubagents);
  }

  public static AgentRuntimeConfig from(AgentSnapshot snapshot) {
    return new AgentRuntimeConfig(
        snapshot.systemPrompt(),
        snapshot.modelId(),
        snapshot.variant(),
        snapshot.tools(),
        snapshot.skills(),
        snapshot.allowedSubagents(),
        snapshot.executionPolicyJson());
  }

  public AgentRuntimeConfig withModel(String modelId, String variant) {
    return new AgentRuntimeConfig(
        systemPrompt, modelId, variant, tools, skills, allowedSubagents, executionPolicyJson);
  }

  public AgentRuntimeConfig withTools(List<String> tools) {
    return new AgentRuntimeConfig(
        systemPrompt, modelId, variant, tools, skills, allowedSubagents, executionPolicyJson);
  }
}
