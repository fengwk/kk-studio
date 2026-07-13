package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Objects;

/** 首次运行时固化的 Agent 配置，保证历史回放不读取当前 AgentDefinition。 */
public record AgentSnapshot(
    String systemPrompt,
    String modelId,
    String variant,
    List<String> tools,
    List<String> skills,
    List<String> allowedSubagents,
    String executionPolicyJson) {
  public AgentSnapshot {
    modelId = requireNonBlank(modelId, "modelId");
    variant = requireNonBlank(variant, "variant");
    tools = copyStrings(tools, "tools");
    skills = copyStrings(skills, "skills");
    allowedSubagents = copyStrings(allowedSubagents, "allowedSubagents");
    executionPolicyJson = requireNonBlank(executionPolicyJson, "executionPolicyJson");
  }

  private static List<String> copyStrings(List<String> values, String name) {
    values = List.copyOf(Objects.requireNonNull(values, name));
    if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(name + " must only contain non-blank values");
    }
    return values;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
