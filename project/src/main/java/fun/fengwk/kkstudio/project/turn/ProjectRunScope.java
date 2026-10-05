package fun.fengwk.kkstudio.project.turn;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Issue Run 在接受阶段冻结到 Harness branch 的权威上下文（Contributor {@code project} 的 {@code run} custom
 * state）。
 *
 * <p>它是 Run 显式编排的唯一事实来源：接受方在分配 runId 后把当前 Issue、阶段职责与 {@code sourceThreadId}（本次业务执行身份） 一起冻结，之后的每个模型
 * turn 直接读取这份快照渲染上下文，运行时不再反查 Thread 属于哪个 Issue。因此一次 Run 的上下文与 后续的 Issue/阶段修改解耦，旧 Run 或从分支继承状态的 fork
 * 也不会因此获得新的业务权限。
 *
 * <p>{@code sourceThreadId} 是业务执行身份，不用于授权本身；{@code issue_transition} 仍会在业务事务内以当前 Run 重新校验调用
 * Thread、Issue、阶段与版本（见 {@code IssueTransitionService}），fork 只因继承快照而没有写权限。
 *
 * <p>{@code active} 显式标记该 Run 是否仍处于执行期：接受时为 {@code true}；Run 进入任何终态时在同一业务锁内接受一条纯
 * SET_CONTRIBUTOR_STATE 批次保存同一 {@code runId} 的 {@code active=false} 副本。这样 branch 上后写的 scope 一定来自更新的
 * Run 生命周期位置，projector 对已关闭 scope 输出空、业务写工具拒绝，从旧 Run 或 fork 继承快照都不会再获得业务权限。
 */
public record ProjectRunScope(
    UUID runId,
    UUID issueId,
    UUID projectId,
    UUID sourceThreadId,
    long issueNumber,
    String issueTitle,
    String issueDescription,
    String stage,
    String stageName,
    String stageInstructions,
    List<String> nextStates,
    String agentName,
    boolean active) {

  /** Contributor id：与 {@code ProjectHarnessContributor.ID} 保持一致。 */
  public static final String CONTRIBUTOR_ID = "project";

  /** Contributor 内 custom state 的 canonical customType。 */
  public static final String CUSTOM_TYPE = "run";

  /** 当前冻结快照的 schema 版本。 */
  public static final int SCHEMA_VERSION = 1;

  /** 唯一业务写工具名：模型可见名，也是交接协议的稳定引用。 */
  public static final String ISSUE_TRANSITION_TOOL = "issue_transition";

  public ProjectRunScope {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(sourceThreadId, "sourceThreadId");
    Objects.requireNonNull(issueTitle, "issueTitle");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(stageName, "stageName");
    Objects.requireNonNull(agentName, "agentName");
    nextStates = List.copyOf(Objects.requireNonNull(nextStates, "nextStates"));
  }

  /** 返回同一 Run 事实的关闭副本：{@code runId} 等身份不变，仅把 {@code active} 置为 false。 */
  public ProjectRunScope closed() {
    return new ProjectRunScope(
        runId,
        issueId,
        projectId,
        sourceThreadId,
        issueNumber,
        issueTitle,
        issueDescription,
        stage,
        stageName,
        stageInstructions,
        nextStates,
        agentName,
        false);
  }

  /**
   * 本 turn 的 Issue Agent 上下文段：当前 Run 冻结的 Issue 要求、阶段职责与交接协议。
   *
   * <p>这些事实是可变的业务数据（人和 Agent 都能通过受控操作修改，但每次 Run 只会冻结一次），因此显式声明为"当前要求"而不是不可协商的 系统指令；状态推进的授权只由 {@link
   * #ISSUE_TRANSITION_TOOL} 在事务中校验，绝不由本段文本授予。
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
                + " state of this run; they were frozen when the run was accepted, so never rely on"
                + " earlier conversation for the current requirements.\n")
        .append("- Request a handoff with `")
        .append(ISSUE_TRANSITION_TOOL)
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
