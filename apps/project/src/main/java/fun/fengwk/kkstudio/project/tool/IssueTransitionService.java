package fun.fengwk.kkstudio.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code issue_transition} 的事务边界：以真实调用 Thread 的稳定绑定定位 Issue，再以该 Issue 的活动 Run 作为唯一授权依据，把交接目标写入
 * {@code project_issue_run.next_state}。
 *
 * <p>模型不提供任何身份：Thread 来自执行上下文，Issue 来自 Project 自己的稳定绑定，Run 来自 Project 自己的活动 Run 记录，因此在
 * prompt、工具参数或历史文本里伪造身份都不起作用。授权边界是「活动 Run 仍存在且 active、其 Thread 就是调用 Thread、其阶段与当前 Issue
 * 阶段一致、且这次调用确实是本 Run 起点之后的 assistant turn」。因此：
 *
 * <ul>
 *   <li>按锁序 Project SHARE → Issue UPDATE → Run UPDATE 读写，避免与接受/收尾路径并发写同一 Run。
 *   <li>锁内读取该 Thread 的真实历史路径，要求 assistant 所属 TURN_START 严格晚于 {@code startEntryId}：已结束 Run 的迟到调用、同一
 *       Thread 后续 Run 期间才落库的旧 assistant turn、兄弟分支与 fork 继承的输入都在写前被拒绝。
 *   <li>只接受当前阶段 workflow {@code next} 白名单中启用的目标；同目标的重复调用是无写操作的幂等重放，异目标在已接受后确定性拒绝。
 *   <li>写入失败（版本 CAS 冲突）整体回滚，绝不留下部分交接事实。
 * </ul>
 */
public class IssueTransitionService {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueThreadExecution threadExecutions;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueTransitionService(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueThreadExecution threadExecutions,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.threadExecutions = Objects.requireNonNull(threadExecutions, "threadExecutions");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  /** 接受一次阶段交接请求；返回已登记的目标与是否为幂等重放。 */
  @Transactional
  public IssueTransitionResult accept(UUID callingThreadId, UUID assistantEntryId, String toState) {
    Objects.requireNonNull(callingThreadId, "callingThreadId");
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    ProjectStateCode target = requireTarget(toState);

    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(callingThreadId);
    if (binding == null) {
      throw new AiValidationException(
          "issue_run", "issue_transition is only available on a thread bound to an Issue agent");
    }
    IssueRun activePeek = issueRunRepository.getActiveByIssueId(binding.issueId());
    if (activePeek == null) {
      throw noActiveRun();
    }
    Issue peekIssue = issueRepository.getById(binding.issueId());
    if (peekIssue == null) {
      throw inconsistent();
    }
    Project project = projectRepository.lockForKeyShare(peekIssue.getProjectId());
    if (project == null) {
      throw inconsistent();
    }
    Issue issue = issueRepository.lockById(binding.issueId());
    if (issue == null) {
      throw inconsistent();
    }
    IssueRun run = issueRunRepository.lockById(activePeek.getId());
    if (run == null || !run.getIssueId().equals(issue.getId())) {
      throw inconsistent();
    }
    if (!run.isActive()) {
      throw noActiveRun();
    }
    if (!run.getThreadId().equals(callingThreadId)) {
      throw new AiValidationException("issue_run", "the active run belongs to a different thread");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Issue is archived");
    }
    requireAssistantTurnOfActiveRun(run, callingThreadId, assistantEntryId);

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

  /**
   * 要求 assistant 落在当前路径，且其所属 Turn 由调用 Thread 创建并严格晚于 Run 起点。
   *
   * <p>同一 Thread 会被后续 Run 复用，"Thread 相同"不足以证明归属：旧 Turn 的 assistant 入口迟到落库后仍能在本 Thread 上取到新 Run，必须检查
   * Turn 起点而不是 assistant 的落库位置。
   */
  private void requireAssistantTurnOfActiveRun(
      IssueRun run, UUID callingThreadId, UUID assistantEntryId) {
    EntryPath path = threadExecutions.readThread(callingThreadId).entryPath();
    List<UUID> entryIds = path.entries().stream().map(Entry::id).toList();
    int start = entryIds.indexOf(run.getStartEntryId());
    if (start < 0) {
      throw inconsistent();
    }
    int assistant = entryIds.indexOf(assistantEntryId);
    if (assistant > start
        && path.entries().get(assistant).payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT) {
      for (int i = assistant - 1; i >= 0; i--) {
        var payload = path.entries().get(i).payload();
        if (payload instanceof TurnEndPayload) {
          break;
        }
        if (payload instanceof TurnStartPayload turn) {
          if (i > start && turn.ownerThreadId().equals(callingThreadId)) {
            return;
          }
          break;
        }
      }
    }
    throw new AiValidationException(
        "issue_run", "issue_transition must be called from the assistant turn of the active run");
  }

  private static AiValidationException noActiveRun() {
    return new AiValidationException(
        "issue_run", "Issue has no active run; a handoff cannot be accepted");
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
