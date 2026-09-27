package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code issue_transition} 的事务边界：校验当前 Run 身份与合法边后，把交接目标写入 {@code project_issue_run.next_state}。
 *
 * <p>交接请求只登记意图，绝不直接改变 {@code Issue.state}：真正推进阶段仍由收尾路径在安全点复核 Run 身份/版本/合法边/门禁后完成（设计 §4.5）。因此本服务：
 *
 * <ul>
 *   <li>按锁序 Project SHARE → Issue UPDATE → 活动 Run 读取并锁定，避免与接受/收尾路径并发写同一 Run。
 *   <li>要求调用 Thread 就是该 Issue 活动 Run 的 Thread，且 Run 阶段与当前 Issue 阶段一致：旧 Run、别的 Agent Thread 与同名阶段
 *       的迟到调用都在写前被拒绝。
 *   <li>只接受当前阶段 workflow {@code next} 白名单中启用的目标；同目标的重复调用是无写操作的幂等重放，异目标在已接受后确定性拒绝。
 *   <li>写入失败（版本 CAS 冲突）整体回滚，绝不留下部分交接事实。
 * </ul>
 */
public class IssueTransitionService {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueTransitionService(
      IssueAgentThreadRepository issueAgentThreadRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  /** 接受一次阶段交接请求；返回已登记的目标与是否为幂等重放。 */
  @Transactional
  public IssueTransitionResult accept(UUID threadId, String toState) {
    Objects.requireNonNull(threadId, "threadId");
    if (toState == null || toState.isBlank() || !toState.equals(toState.strip())) {
      throw new AiValidationException("issue_run", "to_state must be a non-blank state code");
    }
    ProjectStateCode target;
    try {
      target = ProjectStateCode.of(toState);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("issue_run", "invalid to_state: " + toState);
    }

    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(threadId);
    if (binding == null) {
      throw new AiValidationException(
          "issue_agent_thread", "issue_transition is only available on an issue agent thread");
    }
    Issue peek = issueRepository.getById(binding.issueId());
    if (peek == null) {
      throw inconsistent();
    }
    Project project = projectRepository.lockForKeyShare(peek.getProjectId());
    if (project == null) {
      throw inconsistent();
    }
    Issue issue = issueRepository.lockById(binding.issueId());
    if (issue == null) {
      throw inconsistent();
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Issue is archived");
    }
    if (issue.isPaused()) {
      throw new AiValidationException(
          "issue", "Issue is paused; a human must resume it before a handoff can be accepted");
    }

    ProjectWorkflow workflow;
    ProjectStateCode from;
    ProjectWorkflowState source;
    try {
      workflow = workflowCodec.decode(project.getWorkflowJson());
      from = ProjectStateCode.of(issue.getState());
      source = workflow.find(from).orElse(null);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          "project", "invalid project workflow or issue state: " + issue.getState());
    }
    if (source == null || ProjectWorkflowReservedState.isReserved(from)) {
      throw new AiValidationException(
          "issue_run", "Issue is not in an executable work stage: " + issue.getState());
    }

    IssueRun run = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (run == null || !run.isActive()) {
      throw new AiValidationException(
          "issue_run", "Issue has no active run; a handoff cannot be accepted");
    }
    if (!run.getThreadId().equals(threadId)) {
      throw new AiValidationException("issue_run", "the active run belongs to a different thread");
    }
    if (!run.getState().equals(issue.getState())) {
      throw new AiValidationException(
          "issue_run",
          "the active run stage "
              + run.getState()
              + " does not match the current issue stage "
              + issue.getState());
    }

    if (run.getNextState() != null) {
      if (run.getNextState().equals(target.value())) {
        // 缺少响应后的重试：目标已经登记，不重复写行、不推进版本。
        return new IssueTransitionResult(run.getState(), run.getNextState(), run.getId(), true);
      }
      // 已接受交接后不再接受第二个目标：先把"已经登记过别的目标"如实告知，而不是把它当成一次普通的非法边。
      throw new AiValidationException(
          "issue_run", "a different handoff target is already accepted: " + run.getNextState());
    }

    IssueStateTransitions transitions = new IssueStateTransitions(workflow);
    if (!transitions.canTransition(from, target)) {
      throw new AiValidationException(
          "issue_run",
          "state "
              + from
              + " cannot transition to "
              + target
              + "; allowed targets: "
              + source.next().stream().map(ProjectStateCode::value).toList());
    }

    long expectedVersion = run.getVersion();
    run.setNextState(target.value());
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new AiVersionConflictException(
          "issue_run", Long.toString(expectedVersion), Long.toString(run.getVersion()));
    }
    return new IssueTransitionResult(from.value(), target.value(), run.getId(), false);
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }

  /** 一次被接受的交接：目标登记在活动 Run 上，收尾时才推进 Issue 阶段。 */
  public record IssueTransitionResult(
      String fromState, String toState, UUID runId, boolean replayed) {}
}
