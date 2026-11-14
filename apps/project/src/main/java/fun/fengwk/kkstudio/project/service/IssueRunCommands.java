package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次 Run 接受时提交给 Harness 的完整命令批与任务消息。
 *
 * <p>命令顺序固定：先按固定顺序设置 Agent/Execution/Environment，最后以恰一条 CUSTOM_MESSAGE（可信调用方输入，而非人类
 * USER_MESSAGE）启动任务。Environment 取当前 stage 的显式选择（含 null 清除），而不是 Agent 默认 settings 的
 * environmentName，否则工作流里配置的 stage 环境会被 Agent 默认值悄悄覆盖；Agent 与 Model 仍以本 Run 明确物化的 run 配置为准。
 *
 * <p>任务消息是本次 Run 唯一的业务上下文来源：它在接受时由 Project 自己的模板渲染成 Markdown，不写入 branch custom state，不依赖 Harness
 * 状态投影。
 */
final class IssueRunCommands {

  /** 任务消息模板：可信 Markdown，Issue 事实与阶段说明按原文渲染。 */
  private static final String TASK_MESSAGE =
      "fun/fengwk/kkstudio/project/prompts/messages/issue-task.md";

  private IssueRunCommands() {}

  /** 构建本次 Run 的完整命令批，末条即任务输入。 */
  static List<NewThreadCommand> build(
      BranchSettings settings, Issue issue, ProjectWorkflowState stage, UUID runId) {
    return List.of(
        command(new SetAgentCommandPayload(settings.agentName()), runId, "agent"),
        command(new SetExecutionCommandPayload(settings.execution()), runId, "execution"),
        command(new SetEnvironmentCommandPayload(stage.environment()), runId, "environment"),
        command(new CustomMessageCommandPayload(taskInput(issue, stage)), runId, "task"));
  }

  private static NewThreadCommand command(ThreadCommandPayload payload, UUID runId, String role) {
    return new NewThreadCommand(payload, commandKey(runId, role));
  }

  /** 命令幂等键由 Run id 与角色确定：同一 Run 的重复接受不会重复入队。 */
  private static UUID commandKey(UUID runId, String role) {
    return UUID.nameUUIDFromBytes(
        ("issue-run:" + runId + ":" + role).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 自动任务输入：Issue 要求、阶段说明与可选的下一阶段一次渲染成可信 Markdown。
   *
   * <p>事实以三花括号原样插入，Issue 正文与阶段说明里的 Markdown 不会被转义；这些文本是当前 Run 开始时的事实，不是授权来源—— 交接仍只由 {@code
   * issue_transition} 在业务事务内按活动 Run 校验。
   */
  private static AgentMessage taskInput(Issue issue, ProjectWorkflowState stage) {
    List<String> nextStates = stage.next().stream().map(ProjectStateCode::value).toList();
    String description = issue.getDescription();
    String instructions = stage.instructions();
    String stageInstructions =
        instructions == null || instructions.isBlank()
            ? "(this stage declares no additional instructions)"
            : instructions;
    String text =
        PromptRenderer.render(
                TASK_MESSAGE,
                Map.ofEntries(
                    Map.entry("issueNumber", issue.getNumber()),
                    Map.entry("issueTitle", issue.getTitle()),
                    Map.entry("hasDescription", description != null && !description.isBlank()),
                    Map.entry("issueDescription", description == null ? "" : description),
                    Map.entry("stage", stage.state().value()),
                    Map.entry("stageName", stage.name()),
                    Map.entry("stageInstructions", stageInstructions),
                    Map.entry("hasNext", !nextStates.isEmpty()),
                    Map.entry("nextStates", String.join(", ", nextStates))))
            .stripTrailing();
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }
}
