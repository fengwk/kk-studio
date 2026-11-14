package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.ReceiptLock;
import fun.fengwk.kkstudio.work.DurableWorkStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue Run 用例实现：接受与收尾共用 Project SHARE → Issue UPDATE → 已有预算/Run/绑定 → Harness Session/Thread → Work
 * 的锁序。
 *
 * <p>首次接受时先持有产品锁并完成额度检查，通过 Harness NEW_SESSION 创建 Session/ROOT/Thread 与初始命令，再在同一物理事务写稳定 Thread
 * 绑定、Run 与 RUN 活动、Issue Work；已有 Thread 则冻结当前 head 后继续接受本次命令。任一步失败整体回滚，包括已接受的 Harness 初始命令与
 * Work。模型/工具外部调用发生在事务外，锁内不做网络 I/O；部署缺少 Harness Runtime 时确定失败，绝不退回本地假执行。
 *
 * <p>一次 Run 的 Harness 输入只有通用命令（{@code SET_AGENT} / {@code SET_EXECUTION} / {@code
 * SET_ENVIRONMENT}）与一条 由 Project 自己渲染的 Markdown 任务消息：Issue 事实、阶段说明与可交接目标都在消息里，不写任何 branch custom
 * state，也不在收尾时回写 Harness 状态。
 *
 * <p>幂等（设计 §4.6）：每个写操作都要求调用方请求键，活动身份为 {@code kind:requestKey}，指纹含动作与规范化请求字段。锁前检查只是快速路径； owner
 * 锁（Project SHARE → Issue UPDATE → Run）之后、版本/状态/额度校验与 Harness 派发之前必须再查一次 receipt。同键同指纹按原活动精确重放并返回当前
 * Run，不新建 Session/Thread/命令、不重新扣额度、不重复推进状态；同键异指纹确定性冲突。无交接的完成使用 CONTROL/COMPLETE_RUN 活动作为同一事务
 * receipt。 行级"已在目标状态"的重试（WAITING/RUNNING）是无写操作的空重放。重放返回的事实必须是已提交的行：锁前重放在观察到 receipt 之后重新读取权威 Run（见
 * {@link #currentRun(UUID)}），锁内重放使用 owner 锁读到的行；两者都不复用本事务早先的一级缓存快照。
 *
 * <p>收尾额外复验 Run 身份/版本/合法边/门禁与历史区间：区间按 Entry 父链解释（设计 §4.5），仅靠外键只能保证 Entry 属于同一 Session，不能保证 {@code
 * (start,end]} 落在该 Thread 的历史路径上。
 */
@Service
@AllArgsConstructor
public class IssueRunService {

  /** 新接受 Run 的初始活动执行额度；安全等待不消耗活动时长。 */
  static final long DEFAULT_RUN_EXECUTION_BUDGET_MS = 30L * 60L * 1000L;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final DurableWorkStore issueWorkQueue;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final ObjectMapper objectMapper;
  private final IssueRunAdmission admission;
  private final IssueRunHarness harness;

  @Transactional
  public IssueRun acceptRun(UUID issueId, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String acceptKey = InputValidation.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.RUN, "ACCEPT_RUN", acceptKey, issueId);
    if (issueRepository.getById(issueId) == null) {
      throw DomainException.notFound("issue");
    }
    ReceiptLock<OwnerLock> receipt =
        IssueActivityIdempotency.lockUnlessReplayed(
            issueActivityRepository,
            issueId,
            identity,
            () -> lockOwner(issueId),
            l -> l.issue().getId());
    if (receipt.activity() != null) {
      return replayAcceptedRun(receipt.activity());
    }
    Project project = receipt.locked().project();
    Issue issue = receipt.locked().issue();
    IssueRunAdmission.Admitted admitted = admission.admit(project, issue);
    IssueRunHarness.Dispatched dispatched =
        harness.dispatch(project, issue, admitted.stage(), admitted.agentName());

    long ordinal = issue.getNextRunOrdinal();
    // 指示游标沿用上一个 Run 的既有活动事实位置：上一个 Run 未在安全点观察到的业务指示不会被匹配收尾吞掉，会由本次 Run
    // 在安全点投递；没有历史 Run 时才从当前活动流尾部开始。
    IssueRun previousRun = issueRunRepository.getLatestByIssueId(issueId);
    long observedActivitySequence =
        previousRun != null
            ? previousRun.getObservedActivitySequence()
            : issue.getNextActivitySequence() - 1;
    IssueRun run =
        IssueRun.builder()
            .id(dispatched.runId())
            .issueId(issueId)
            .ordinal(ordinal)
            .state(issue.getState())
            .sessionId(dispatched.sessionId())
            .threadId(dispatched.threadId())
            .status(IssueRunStatus.RUNNING)
            .startEntryId(dispatched.startEntryId())
            .observedActivitySequence(observedActivitySequence)
            .remainingExecutionMs(DEFAULT_RUN_EXECUTION_BUDGET_MS)
            .activeSince(Instant.now())
            .build();
    if (!issueRunRepository.insert(run)) {
      throw new IllegalStateException("failed to insert issue run");
    }
    appendActivity(
        issue,
        identity,
        IssueActivityActorType.SYSTEM,
        null,
        run.getId(),
        null,
        objectMapper.createObjectNode());
    issue.setNextRunOrdinal(ordinal + 1);
    if (!issueRepository.updateById(issue, issue.getVersion())) {
      throw DomainException.versionConflict("issue", issue.getVersion(), issue.getVersion());
    }
    issueWorkQueue.request(issueId, Duration.ZERO);
    return issueRunRepository.getById(run.getId());
  }

  /** 精确重试：返回原 RUN 活动引用的 Run，不新建 Session/Thread/命令，也不重新校验阶段额度或推进 ordinal。 */
  private IssueRun replayAcceptedRun(IssueActivity applied) {
    if (applied.getRunId() == null) {
      throw new IllegalStateException("RUN activity is missing its run reference");
    }
    IssueRun run = issueRunRepository.getById(applied.getRunId());
    if (run == null) {
      throw new IllegalStateException("accepted run does not exist");
    }
    return run;
  }

  public List<IssueRun> listRuns(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueRunRepository.listByIssueId(issueId);
  }

  @Transactional
  public IssueRun waitRun(UUID runId, long expectedVersion) {
    RunLock locked = lockRun(runId);
    IssueRun run = locked.run();
    if (run.getStatus() == IssueRunStatus.WAITING) {
      // 已经是安全等待：不重复写行，也不再校验调用方版本。
      return issueRunRepository.getById(runId);
    }
    requireActive(run);
    requireVersion(run, expectedVersion);
    if (run.getRemainingExecutionMs() <= 0) {
      throw DomainException.validation(
          "issue_run", "Cannot wait: run has no execution budget left");
    }
    run.setStatus(IssueRunStatus.WAITING);
    run.setActiveSince(null);
    updateRun(run, expectedVersion);
    return issueRunRepository.getById(runId);
  }

  @Transactional
  public IssueRun resumeRun(UUID runId, long expectedVersion) {
    RunLock locked = lockRun(runId);
    IssueRun run = locked.run();
    if (run.getStatus() == IssueRunStatus.RUNNING) {
      // 已经是执行状态：不重复写行，也不再校验调用方版本。
      return issueRunRepository.getById(runId);
    }
    if (run.getStatus() != IssueRunStatus.WAITING) {
      throw DomainException.validation("issue_run", "Only a WAITING run can be resumed");
    }
    requireVersion(run, expectedVersion);
    if (locked.issue().isGateClosed()) {
      throw DomainException.validation(
          "issue_run", "Cannot resume a run while the issue gate is closed");
    }
    if (run.getRemainingExecutionMs() <= 0) {
      throw DomainException.validation(
          "issue_run", "Cannot resume: run has no execution budget left");
    }
    // 恢复消耗的是本次 Run 已接受的活动额度，不新建 Run、不重查阶段额度。
    run.setStatus(IssueRunStatus.RUNNING);
    run.setActiveSince(Instant.now());
    updateRun(run, expectedVersion);
    return issueRunRepository.getById(runId);
  }

  @Transactional
  public IssueRun finishRun(
      UUID runId,
      long expectedVersion,
      String requestKey,
      IssueRunStatus status,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      String nextState,
      String error) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(endEntryId, "endEntryId");
    IssueRunTerminal terminal =
        IssueRunTerminal.parse(
            runId, requestKey, status, endEntryId, finalAnswerEntryId, nextState, error);
    IssueRun peek = requireRun(runId);
    ReceiptLock<RunLock> receipt =
        IssueActivityIdempotency.lockUnlessReplayed(
            issueActivityRepository,
            peek.getIssueId(),
            terminal.identity(),
            () -> lockRun(runId),
            l -> l.issue().getId());
    if (receipt.activity() != null) {
      return receipt.locked() == null ? currentRun(runId) : receipt.locked().run();
    }
    RunLock locked = receipt.locked();
    IssueRun run = locked.run();
    requireActive(run);
    requireVersion(run, expectedVersion);
    IssueRunInterval.requireWithin(
        harness.entryPath(run.getThreadId()),
        run.getStartEntryId(),
        endEntryId,
        finalAnswerEntryId,
        terminal.completed());
    if (!terminal.completed()) {
      terminate(run, terminal.status(), endEntryId, terminal.error(), expectedVersion);
      applyPauseGate(locked, terminal.identity(), terminal.pauseReason(), terminal.pauseDetail());
      return issueRunRepository.getById(runId);
    }
    Issue issue = locked.issue();
    if (terminal.handoff()) {
      ProjectWorkflow workflow = workflowCodec.decode(locked.project().getWorkflowJson());
      ProjectStateCode from = ProjectStateCode.of(run.getState());
      ProjectStateCode to = ProjectStateCode.of(nextState);
      new IssueStateTransitions(workflow).requireTransition(from, to);
      ObjectNode data = objectMapper.createObjectNode();
      data.put("action", "HANDOFF");
      data.put("from", from.value());
      data.put("to", to.value());
      appendActivity(
          issue, terminal.identity(), IssueActivityActorType.SYSTEM, null, null, null, data);
      issue.setState(to.value());
    } else {
      ObjectNode data = objectMapper.createObjectNode();
      data.put("action", "COMPLETE_RUN");
      appendActivity(
          issue, terminal.identity(), IssueActivityActorType.SYSTEM, null, runId, null, data);
    }
    run.setStatus(IssueRunStatus.COMPLETED);
    run.setEndEntryId(endEntryId);
    run.setFinalAnswerEntryId(finalAnswerEntryId);
    run.setNextState(nextState);
    run.setActiveSince(null);
    run.setEndedAt(Instant.now());
    run.setError(null);
    updateRun(run, expectedVersion);
    persistIssue(issue, locked.issueVersion());
    return issueRunRepository.getById(runId);
  }

  private void terminate(
      IssueRun run, IssueRunStatus status, UUID endEntryId, String error, long expectedVersion) {
    run.setStatus(status);
    run.setEndEntryId(endEntryId);
    run.setFinalAnswerEntryId(null);
    run.setActiveSince(null);
    run.setEndedAt(Instant.now());
    run.setError(error);
    updateRun(run, expectedVersion);
  }

  private void applyPauseGate(
      RunLock locked, Identity identity, PauseReason reason, String detail) {
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "PAUSE");
    data.put("reason", reason.name());
    appendActivity(locked.issue(), identity, IssueActivityActorType.SYSTEM, null, null, null, data);
    locked.issue().setPauseReason(reason.name());
    locked.issue().setPauseDetail(detail);
    persistIssue(locked.issue(), locked.issueVersion());
  }

  private void requireActive(IssueRun run) {
    if (!run.isActive()) {
      throw DomainException.validation("issue_run", "Run is already terminal");
    }
  }

  private void requireVersion(IssueRun run, long expectedVersion) {
    if (run.getVersion() != expectedVersion) {
      throw DomainException.versionConflict("issue_run", expectedVersion, run.getVersion());
    }
  }

  private void updateRun(IssueRun run, long expectedVersion) {
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw DomainException.versionConflict("issue_run", expectedVersion, run.getVersion());
    }
  }

  private void persistIssue(Issue issue, long expectedVersion) {
    if (!issueRepository.updateById(issue, expectedVersion)) {
      throw DomainException.versionConflict("issue", expectedVersion, issue.getVersion());
    }
  }

  private OwnerLock lockOwner(UUID issueId) {
    Issue initial = issueRepository.getById(issueId);
    // Session acceptance takes Project SHARE so it serializes with workflow and archive updates.
    Project project = projectRepository.lockForShare(initial.getProjectId());
    if (project == null) {
      throw DomainException.notFound("project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw DomainException.notFound("issue");
    }
    if (!issue.getProjectId().equals(project.getId())) {
      throw DomainException.validation("issue", "Issue hierarchy is inconsistent");
    }
    return new OwnerLock(project, issue);
  }

  private RunLock lockRun(UUID runId) {
    IssueRun peek = requireRun(runId);
    Issue peekIssue = issueRepository.getById(peek.getIssueId());
    if (peekIssue == null) {
      throw DomainException.notFound("issue");
    }
    Project project = projectRepository.lockForKeyShare(peekIssue.getProjectId());
    if (project == null) {
      throw DomainException.notFound("project");
    }
    Issue issue = issueRepository.lockById(peekIssue.getId());
    if (issue == null) {
      throw DomainException.notFound("issue");
    }
    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw DomainException.notFound("issue_run");
    }
    return new RunLock(run, issue, issue.getVersion(), project);
  }

  /**
   * 锁前重放返回的权威当前 Run：在观察到 receipt 之后重新读取已提交的行，不会返回本事务早先读到的旧快照。
   *
   * <p>每次读取都是新的语句快照：并发首次收尾完全可能落在那次读取与本事务的 receipt 检查之间，因此重放结果必须来自这里的权威读。
   */
  private IssueRun currentRun(UUID runId) {
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw DomainException.notFound("issue_run");
    }
    return run;
  }

  private IssueRun requireRun(UUID runId) {
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw DomainException.notFound("issue_run");
    }
    return run;
  }

  /**
   * 追加一条业务活动（设计 §4.6 一个动作一条活动）：请求键与指纹来自调用方身份，正文与 Run 引用按动作填写。
   *
   * <p>调用方必须在 owner 锁内、版本/状态校验与 Harness 派发之前完成 {@link IssueActivityIdempotency#findApplied
   * 精确重试判定}；本方法只在首次执行路径调用。写完活动后必须在同一事务内以版本 CAS 写回 Issue 行。
   */
  private void appendActivity(
      Issue issue,
      Identity identity,
      IssueActivityActorType actorType,
      String agentName,
      UUID runId,
      String body,
      ObjectNode data) {
    long sequence = issue.getNextActivitySequence();
    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issue.getId())
            .sequence(sequence)
            .kind(identity.kind())
            .actorType(actorType)
            .actorAgentName(agentName)
            .runId(runId)
            .body(body)
            .data(IssueMutationSupport.writeJson(objectMapper, data))
            .idempotencyKey(identity.key())
            .requestHash(identity.requestHash())
            .build();
    if (!issueActivityRepository.insert(activity)) {
      throw new IllegalStateException("failed to insert issue activity");
    }
    issue.setNextActivitySequence(sequence + 1);
  }

  private record OwnerLock(Project project, Issue issue) {}

  private record RunLock(IssueRun run, Issue issue, long issueVersion, Project project) {}
}
