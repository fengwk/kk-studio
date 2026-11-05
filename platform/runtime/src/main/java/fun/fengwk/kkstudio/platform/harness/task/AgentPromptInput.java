package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单次 system prompt 渲染的全部输入事实：Agent 正文、当前 Environment、执行根会话、当前线程、实际注入的工具、Skills 与子 Agent。
 *
 * <p>执行根会话与当前线程不同：子 Agent 在独立会话中运行，但 VFS 会话目录始终按执行根会话解析；{@code threadId} 只用于拼出当前线程在 执行根会话下的 {@code
 * thread_dir}。
 */
public record AgentPromptInput(
    String agentInstructions,
    CurrentEnvironmentContext currentEnvironment,
    UUID rootSessionId,
    UUID threadId,
    List<String> toolNames,
    List<SkillPromptEntry> skills,
    List<SubagentBinding> subagents) {

  public AgentPromptInput {
    currentEnvironment = Objects.requireNonNull(currentEnvironment, "currentEnvironment");
    rootSessionId = Objects.requireNonNull(rootSessionId, "rootSessionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    toolNames = List.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    subagents = List.copyOf(Objects.requireNonNull(subagents, "subagents"));
  }
}
