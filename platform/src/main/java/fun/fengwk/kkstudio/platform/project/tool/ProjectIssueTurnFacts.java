package fun.fengwk.kkstudio.platform.project.tool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Issue Agent Thread 当前 turn 的权威目标事实：稳定归属、当前阶段与由实时 Project workflow 派生的阶段职责。
 *
 * <p>事实只来自本 Thread 自身的稳定绑定、Issue 行、Project workflow 与活动 Run，因此天然不可跨 Thread 泄漏；不含任何历史对话、其他 Agent
 * 的报告或旧 Run 的角色快照。{@code environmentName} 来自当前阶段配置，null 表示该阶段未配置 Environment（必须清除本 turn 的
 * 环境选择，而不是沿用 branch 上的历史快照）。
 */
public record ProjectIssueTurnFacts(
    UUID issueId,
    UUID projectId,
    UUID runId,
    long issueNumber,
    String issueTitle,
    String issueDescription,
    String stage,
    String stageName,
    String stageInstructions,
    List<String> nextStates,
    String environmentName,
    String agentName) {

  public ProjectIssueTurnFacts {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(issueTitle, "issueTitle");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(stageName, "stageName");
    Objects.requireNonNull(agentName, "agentName");
    nextStates = List.copyOf(Objects.requireNonNull(nextStates, "nextStates"));
  }

  /**
   * 本 turn 的 Issue Agent 上下文段：当前 Issue 要求、当前阶段职责与交接协议。
   *
   * <p>这些事实是可变的业务数据（人和 Agent 都能通过受控操作修改），因此显式声明为"当前要求"而不是不可协商的系统指令；状态推进的授权只由 {@code
   * issue_transition} 在事务中校验，绝不由本段文本授予。
   */
  public String contextSection() {
    StringBuilder section = new StringBuilder();
    section
        .append("# Issue Agent Context\n\n")
        .append("- issue_id: ")
        .append(issueId)
        .append('\n')
        .append("- project_id: ")
        .append(projectId)
        .append('\n')
        .append("- run_id: ")
        .append(runId)
        .append('\n')
        .append("- stage: ")
        .append(stage)
        .append(" (")
        .append(stageName)
        .append(")\n")
        .append("- agent_name: ")
        .append(agentName)
        .append("\n\n")
        .append("## Current Issue\n\n")
        .append("#")
        .append(issueNumber)
        .append(' ')
        .append(issueTitle);
    if (issueDescription != null && !issueDescription.isBlank()) {
      section.append("\n\n").append(issueDescription);
    }
    section.append("\n\n## Stage Instructions\n\n");
    if (stageInstructions == null || stageInstructions.isBlank()) {
      section.append("(this stage declares no additional instructions)");
    } else {
      section.append(stageInstructions);
    }
    section
        .append("\n\n## Handoff\n\n")
        .append(
            "- The Issue requirements and stage instructions above are the authoritative current"
                + " state of this run; they may have been updated since the run started, so never"
                + " rely on earlier conversation.\n")
        .append("- Request a handoff with `")
        .append(IssueTransitionTool.NAME)
        .append(
            "` only when the current stage work is done; the target is stored on the current"
                + " run and takes effect when the run is safely closed out.\n");
    if (nextStates.isEmpty()) {
      section.append("- This stage declares no legal next stage.\n");
    } else {
      section
          .append("- Allowed handoff targets: ")
          .append(String.join(", ", nextStates))
          .append(".\n");
    }
    section.append(
        "- After a handoff has been accepted, do not start new business writes; finish the run with"
            + " a final report of what was done and how it was verified.");
    return section.toString();
  }
}
