package fun.fengwk.kkstudio.platform.harness.thread.command;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Turn 规划中与执行模型无关的 Agent 目录快照。
 *
 * <p>Agent definition/config、当前 Environment、Skills、Subagent bindings、工具 bindings 与最终 system
 * instruction 都来自同一份已租用 catalog 快照。Builtin 规划只在这份快照上解析模型、缓存与预算；这里不包含任何 Provider 或 ModelRequestSpec
 * 事实。
 */
public record AgentTurnContext(
    AgentDefinition agent,
    AgentDefinitionConfigDTO agentConfig,
    CurrentEnvironmentContext currentEnvironment,
    List<LiveTurnPlan.PlannedSkill> skills,
    List<SubagentBinding> subagentBindings,
    List<ToolBinding> toolBindings,
    List<LiveTurnPlan.CandidateTool> candidateTools,
    String systemInstruction) {

  public AgentTurnContext {
    agent = Objects.requireNonNull(agent, "agent");
    agentConfig = Objects.requireNonNull(agentConfig, "agentConfig");
    currentEnvironment = Objects.requireNonNull(currentEnvironment, "currentEnvironment");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    candidateTools = List.copyOf(Objects.requireNonNull(candidateTools, "candidateTools"));
    systemInstruction = Objects.requireNonNull(systemInstruction, "systemInstruction");
  }
}
