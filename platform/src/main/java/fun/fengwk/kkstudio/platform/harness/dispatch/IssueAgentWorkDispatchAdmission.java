package fun.fengwk.kkstudio.platform.harness.dispatch;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;

import java.util.Objects;

/**
 * Issue+Agent 的产品派发门禁：只有「稳定归属 + Issue/Project 事实 + 当前阶段职责 + 活动 Run 坐标 + 非收尾阶段」全部成立，才允许开始一次 新的对外执行。
 *
 * <p>判定顺序与 {@code DatabaseProjectIssueTurnResolver} 保持一致，每一步都失败关闭：Thread 无绑定即 Chat / 内部委派（没有产品暂停语义，
 * 放行）；绑定存在但 Issue/Project 行缺失属于归属事实损坏，拒绝而不是退化成无限放行；Issue 已归档、Project 已归档、Issue 处于控制暂停或当前
 * 阶段是保留状态（INIT / BLOCKED / DONE）都拒绝。
 *
 * <p>即使是绑定的 Thread，也必须是当前活动 Run 的 Agent 坐标：活动 Run 必须存在且仍 active，其 Thread、Session 与阶段必须与该 Thread
 * 精确一致，且当前阶段仍指派给该 Agent。旧 Run、别的 Thread/Session、别的阶段的迟到执行都不借当前权限开始新的对外执行。
 *
 * <p>正在进入下一状态（活动 Run 的 {@code nextState} 已登记）时，本 Run 已进入收尾：禁止业务工具写，但允许模型收尾与既有结果物化。工具是否 只读由冻结绑定自带的
 * {@code descriptor().sideEffect()} 判定——它是该次调用冻结的能力声明，不随目录变化，也不需要额外查询；无法判定归属或 只读性的新工具派发一律拒绝（fail
 * closed）。已在途的执行不受此门禁影响：本门禁只被「首次对外执行」的 claim 询问，暂停或收尾都不会阻断在途 模型/工具的轮询、恢复与取消，因此暂停始终能安全收敛。
 *
 * <p>局限：本判定与宿主随后的产品状态变更之间存在 TOCTOU 窗口（判定通过后状态可能立即改变，本次执行仍会开始）；强一致需要产品侧在同一事务内 冻结判定，本门禁只缩小窗口。
 */
@Service
public class IssueAgentWorkDispatchAdmission implements WorkDispatchAdmission {

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueRepository issueRepository;
  private final ProjectRepository projectRepository;
  private final IssueRunRepository issueRunRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueAgentWorkDispatchAdmission(
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueRepository issueRepository,
      ProjectRepository projectRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  @Override
  @Transactional(readOnly = true)
  public boolean admits(WorkDispatchRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.type() == WorkTargetType.THREAD) {
      // 防御：THREAD Work 不产生对外执行，dispatcher 不会为它询问宿主。
      return true;
    }
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(request.threadId());
    if (binding == null) {
      return true;
    }
    Issue issue = issueRepository.getById(binding.issueId());
    if (issue == null) {
      return false;
    }
    Project project = projectRepository.getById(issue.getProjectId());
    if (project == null || project.isArchived() || issue.isArchived()) {
      return false;
    }
    if (issue.isPaused()) {
      return false;
    }
    ProjectWorkflowState stage = currentStage(project, issue);
    if (stage == null) {
      return false;
    }
    if (!stage.agent().equals(binding.agentName())) {
      return false;
    }
    IssueRun run = issueRunRepository.getActiveByIssueId(issue.getId());
    if (run == null || !run.isActive()) {
      return false;
    }
    if (!run.getThreadId().equals(request.threadId())
        || !run.getSessionId().equals(request.sessionId())
        || !run.getState().equals(issue.getState())) {
      return false;
    }
    if (run.getNextState() == null) {
      return true;
    }
    if (request.type() == WorkTargetType.MODEL) {
      return true;
    }
    // 收尾阶段：只读工具可以继续观察事实，业务写工具与无法判定只读性的工具一律拒绝新的派发。
    return request.toolBinding().descriptor().sideEffect() == ToolSideEffect.READ_ONLY;
  }

  /** 当前阶段必须是启用的工作阶段且配置了 Agent；保留状态、停用阶段与无 Agent 阶段都不产生新的对外执行。 */
  private ProjectWorkflowState currentStage(Project project, Issue issue) {
    ProjectWorkflow workflow;
    ProjectStateCode state;
    try {
      workflow = workflowCodec.decode(project.getWorkflowJson());
      state = ProjectStateCode.of(issue.getState());
    } catch (IllegalArgumentException error) {
      return null;
    }
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return null;
    }
    ProjectWorkflowState stage = workflow.find(state).orElse(null);
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return null;
    }
    return stage;
  }
}
