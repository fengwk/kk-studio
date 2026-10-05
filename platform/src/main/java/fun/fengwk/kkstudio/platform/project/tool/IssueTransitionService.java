package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code issue_transition} 的事务边界：以 Run 冻结快照里的 runId 为入口，校验当前 Run 有效、Issue 阶段与调用 Thread 身份后， 把交接目标写入
 * {@code project_issue_run.next_state}。
 *
 * <p>授权只以数据库中的当前 Run 为准，绝不相信 prompt 中的 runId 或继承来的 branch 快照：runId 只是定位符，真正的边界是 「活动 Run 仍存在且
 * active、其 Thread 就是调用 Thread、其 Issue/阶段与 scope/当前 Issue 一致」。因此：
 *
 * <ul>
 *   <li>按锁序 Project SHARE → Issue UPDATE → Run UPDATE 读写，避免与接受/收尾路径并发写同一 Run。
 *   <li>要求 {@code callingThreadId == scope.sourceThreadId} 且等于 Run 的 Thread：旧 Run、别的 Agent Thread
 *       以及继承 scope 的 fork 都在写前被拒绝。
 *   <li>只接受当前阶段 workflow {@code next} 白名单中启用的目标；同目标的重复调用是无写操作的幂等重放，异目标在已接受后确定性拒绝。
 *   <li>写入失败（版本 CAS 冲突）整体回滚，绝不留下部分交接事实。
 * </ul>
 */
public class IssueTransitionService {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";
  private static final ProjectRunScopeJsonCodec RUN_SCOPE_CODEC = new ProjectRunScopeJsonCodec();

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueTransitionService(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  /** 接受一次阶段交接请求；返回已登记的目标与是否为幂等重放。 */
  @Transactional
  public IssueTransitionResult accept(
      UUID callingThreadId, int scopeSchemaVersion, String runScopeJson, String toState) {
    Objects.requireNonNull(callingThreadId, "callingThreadId");
    if (scopeSchemaVersion != ProjectRunScope.SCHEMA_VERSION) {
      throw new AiValidationException(
          "issue_run", "unsupported run context schema version: " + scopeSchemaVersion);
    }
    ProjectRunScope scope = decodeScope(runScopeJson);
    if (!scope.sourceThreadId().equals(callingThreadId)) {
      throw new AiValidationException(
          "issue_run", "issue_transition is only available on the run's own thread");
    }
    ProjectStateCode target = requireTarget(toState);

    IssueRun runPeek = issueRunRepository.getById(scope.runId());
    if (runPeek == null || !runPeek.getIssueId().equals(scope.issueId())) {
      throw inconsistent();
    }
    Issue peek = issueRepository.getById(scope.issueId());
    if (peek == null) {
      throw inconsistent();
    }
    Project project = projectRepository.lockForKeyShare(peek.getProjectId());
    if (project == null) {
      throw inconsistent();
    }
    Issue issue = issueRepository.lockById(scope.issueId());
    if (issue == null) {
      throw inconsistent();
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Issue is archived");
    }
    IssueRun run = issueRunRepository.lockById(scope.runId());
    if (run == null || !run.isActive()) {
      throw new AiValidationException(
          "issue_run", "Issue has no active run; a handoff cannot be accepted");
    }
    if (!run.getThreadId().equals(callingThreadId)) {
      throw new AiValidationException("issue_run", "the active run belongs to a different thread");
    }
    if (!run.getState().equals(issue.getState()) || !run.getState().equals(scope.stage())) {
      throw new AiValidationException(
          "issue_run",
          "the active run stage "
              + run.getState()
              + " does not match the current issue stage "
              + issue.getState());
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

  private static ProjectRunScope decodeScope(String runScopeJson) {
    try {
      return RUN_SCOPE_CODEC.decode(runScopeJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("issue_run", "invalid run context: " + error.getMessage());
    }
  }

  private static ProjectStateCode requireTarget(String toState) {
    if (toState == null || toState.isBlank() || !toState.equals(toState.strip())) {
      throw new AiValidationException("issue_run", "to_state must be a non-blank state code");
    }
    try {
      return ProjectStateCode.of(toState);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("issue_run", "invalid to_state: " + toState);
    }
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }

  /** 一次被接受的交接：目标登记在活动 Run 上，收尾时才推进 Issue 阶段。 */
  public record IssueTransitionResult(
      String fromState, String toState, UUID runId, boolean replayed) {}
}
