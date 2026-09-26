package fun.fengwk.kkstudio.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Project 领域测试共用的最小 fixture：设计文档形状的 workflow 与合法 Run。 */
final class ProjectDomainFixtures {

  static final Instant START = Instant.parse("2026-09-27T00:00:00Z");

  private ProjectDomainFixtures() {}

  /** 设计文档示例形状：INIT -> DESIGN -> REVIEW -> DONE，BLOCKED 通过专用操作进入。 */
  static ProjectWorkflow documentedWorkflow() {
    return new ProjectWorkflow(
        List.of(
            reservedState("INIT", "待开始", "DESIGN"),
            agentState("DESIGN", "designer", List.of("REVIEW")),
            agentState("REVIEW", "reviewer", List.of("DESIGN", "DONE")),
            reservedState("BLOCKED", "业务阻塞"),
            reservedState("DONE", "完成")));
  }

  /** 保留状态条目：只声明显示名与正常边。 */
  static ProjectWorkflowState reservedState(String code, String name, String... next) {
    return new ProjectWorkflowState(
        ProjectStateCode.of(code), name, null, null, null, null, true, codes(next));
  }

  /** 有 Agent 的工作阶段：必须有正数 maxRuns。 */
  static ProjectWorkflowState agentState(String code, String agent, List<String> next) {
    return new ProjectWorkflowState(
        ProjectStateCode.of(code), code, agent, null, "instructions", 3, true, codes(next));
  }

  /** 无 Agent 的人工阶段：不配置 Environment 与 Run 额度。 */
  static ProjectWorkflowState manualState(String code, boolean enabled, List<String> next) {
    return new ProjectWorkflowState(
        ProjectStateCode.of(code), code, null, null, null, null, enabled, codes(next));
  }

  /** 含人工阶段的 workflow：人工阶段不产生 Run，因此不能授权阶段额度。 */
  static ProjectWorkflow manualWorkflow() {
    return new ProjectWorkflow(
        List.of(
            reservedState("INIT", "待开始", "MANUAL", "DONE"),
            manualState("MANUAL", true, List.of("DONE")),
            reservedState("BLOCKED", "业务阻塞"),
            reservedState("DONE", "完成")));
  }

  /** 含停用阶段的 workflow：停用编码只保留历史语义，不能作为正常边目标或新授权对象。 */
  static ProjectWorkflow disabledStageWorkflow() {
    return new ProjectWorkflow(
        List.of(
            reservedState("INIT", "待开始", "WORK", "DONE"),
            new ProjectWorkflowState(
                ProjectStateCode.of("WORK"), "工作", "worker", null, null, 3, false, codes("DONE")),
            reservedState("BLOCKED", "业务阻塞"),
            reservedState("DONE", "完成")));
  }

  static List<ProjectStateCode> codes(List<String> values) {
    return values.stream().map(ProjectStateCode::of).toList();
  }

  static List<ProjectStateCode> codes(String... values) {
    return codes(List.of(values));
  }

  /** 构造一个形状合法的 Run：活动 Run 保持计时与额度，终态 Run 冻结结束区间与时间。 */
  static IssueRun run(UUID issueId, String state, long ordinal, IssueRunStatus status) {
    UUID startEntryId = id(100L + ordinal);
    IssueRunEntryBounds bounds =
        status.isActive()
            ? new IssueRunEntryBounds(startEntryId, null, null)
            : new IssueRunEntryBounds(startEntryId, id(200L + ordinal), null);
    return new IssueRun(
        id(ordinal),
        issueId,
        ordinal,
        ProjectStateCode.of(state),
        id(900L),
        id(901L),
        status,
        bounds,
        null,
        0L,
        60_000L,
        status == IssueRunStatus.RUNNING ? START : null,
        status == IssueRunStatus.FAILED || status == IssueRunStatus.UNKNOWN ? "boom" : null,
        START,
        status.isActive() ? null : START.plusSeconds(1));
  }

  static UUID id(long value) {
    return new UUID(0L, value);
  }
}
