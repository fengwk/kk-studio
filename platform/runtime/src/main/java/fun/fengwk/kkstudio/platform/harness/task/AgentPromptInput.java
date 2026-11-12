package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;

import java.util.List;
import java.util.Objects;

/** 单次 system prompt 渲染的全部输入事实：Agent 正文、当前 Environment、实际注入的工具、Skills 与子 Agent。 */
public record AgentPromptInput(
    String agentInstructions,
    CurrentEnvironmentContext currentEnvironment,
    List<String> toolNames,
    List<SkillPromptEntry> skills,
    List<SubagentBinding> subagents) {

  public AgentPromptInput {
    currentEnvironment = Objects.requireNonNull(currentEnvironment, "currentEnvironment");
    toolNames = List.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    subagents = List.copyOf(Objects.requireNonNull(subagents, "subagents"));
  }
}
