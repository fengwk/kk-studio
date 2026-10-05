package fun.fengwk.kkstudio.project.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.IssueStageBudget;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.IssueStageBudgetRow;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.port.AgentBranchSettingsPort;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.project.service.impl.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.nio.charset.StandardCharsets;
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
public class IssueRunServiceImpl implements IssueRunService {

  /** 新接受 Run 的初始活动执行额度；安全等待不消耗活动时长。 */
  static final long DEFAULT_RUN_EXECUTION_BUDGET_MS = 30L * 60L * 1000L;

  private static final int MAX_REQUEST_KEY_LENGTH = 128;

  /** Run 冻结快照的规范编解码器：无状态纯函数，Project 与渲染它的宿主组件共享同一份实现。 */
  private static final ProjectRunScopeJsonCodec RUN_SCOPE_CODEC = new ProjectRunScopeJsonCodec();

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueWorkStore issueWorkStore;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final AgentBranchSettingsPort agentBranchSettingsPort;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public IssueRun acceptRun(UUID issueId, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String acceptKey = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.RUN, "ACCEPT_RUN", acceptKey, issueId);
    if (issueRepository.getById(issueId) == null) {
      throw new ProjectNotFoundException("issue");
    }
    IssueActivity applied =
        IssueActivityIdempotency.findApplied(issueActivityRepository, issueId, identity);
    if (applied != null) {
      return replayAcceptedRun(applied);
    }
    Issue initial = issueRepository.getById(issueId);
    Project project = projectRepository.lockForKeyShare(initial.getProjectId());
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(project.getId())) {
      throw new ProjectValidationException("issue", "Issue hierarchy is inconsistent");
    }
    applied =
        IssueActivityIdempotency.findAppliedUnderLock(issueActivityRepository, issueId, identity);
    if (applied != null) {
      return replayAcceptedRun(applied);
    }
    if (project.isArchived()) {
      throw new ProjectValidationException("project", "Cannot accept a run in an archived project");
    }
    if (issue.isArchived()) {
      throw new ProjectValidationException("issue", "Cannot accept a run for an archived issue");
    }
    if (issue.isGateClosed()) {
      throw new ProjectValidationException(
          "issue", "Cannot accept a run while the issue gate is closed");
    }
    ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
    ProjectStateCode stateCode = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(stateCode)) {
      throw new ProjectValidationException(
          "issue_run", "Issue is not in an executable work stage: " + issue.getState());
    }
    ProjectWorkflowState stage = workflow.require(stateCode);
    if (!stage.enabled() || !stage.hasAgent()) {
      throw new ProjectValidationException(
          "issue_run", "Stage " + issue.getState() + " has no enabled agent");
    }
    String agentName = stage.agent();
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException("issue_run", "Issue already has an active run");
    }

    IssueStageBudgetRow budgetRow = stageBudgetRepository.get(issueId, issue.getState());
    if (budgetRow == null) {
      IssueStageBudget authorized =
          IssueStageBudget.authorize(workflow, stateCode, stage.maxRuns());
      budgetRow =
          IssueStageBudgetRow.builder()
              .issueId(issueId)
              .state(issue.getState())
              .maxRuns(authorized.maxRuns())
              .budgetAfterOrdinal(authorized.budgetAfterOrdinal())
              .build();
      if (!stageBudgetRepository.insert(budgetRow)) {
        throw new IllegalStateException("failed to authorize initial stage budget");
      }
    }
    long used =
        issueRunRepository.countByIssueIdAndStateAfterOrdinal(
            issueId, issue.getState(), budgetRow.getBudgetAfterOrdinal());
    if (used >= budgetRow.getMaxRuns()) {
      throw new ProjectValidationException(
          "issue_run", "Stage budget is exhausted; a human must authorize more runs");
    }

    IssueAgentThread binding =
        issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, agentName);
    BranchSettings settings = agentBranchSettingsPort.materializeBranchSettings(agentName);
    // Run id 在接受源输入之前分配，同时作为 root Join ticket 的 invocationId；接受、Join 与 Run 记录在同一事务写入。
    UUID runId = UUID.randomUUID();
    UUID sessionId;
    UUID threadId;
    UUID existingHead = null;
    AcceptCommandsTarget target;
    if (binding == null) {
      sessionId = UUID.randomUUID();
      threadId = UUID.randomUUID();
      target =
          new AcceptCommandsTarget.NewSession(
              sessionId, threadId, settings, null, project.isYoloEnabled());
    } else {
      threadId = binding.threadId();
      ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(threadId);
      sessionId = snapshot.thread().sessionId();
      existingHead = snapshot.thread().headEntryId();
      target =
          new AcceptCommandsTarget.Thread(
              threadId, existingHead, snapshot.thread().nextCommandSequence());
    }

    ProjectRunScope scope = runScope(issue, stage, agentName, runId, threadId);
    List<NewThreadCommand> commands = buildRunCommands(settings, scope, stage.environment());
    NewThreadCommand taskInput = commands.getLast();
    ThreadJoinRequest join =
        new ThreadJoinRequest(runId, null, null, taskInput.requestHash(), agentName, null, 1, 1, 1);
    AcceptedCommands accepted =
        requireRuntime()
            .acceptCommandsAndJoin(
                new AcceptCommandsCommand(target, commands), join, AcceptancePreflight.IDENTITY);
    UUID startEntryId;
    if (binding == null) {
      if (!issueAgentThreadRepository.insert(new IssueAgentThread(issueId, agentName, threadId))) {
        throw new IllegalStateException("failed to bind issue agent thread");
      }
      startEntryId = accepted.rootEntry().id();
    } else {
      startEntryId = existingHead;
    }

    long ordinal = issue.getNextRunOrdinal();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .state(issue.getState())
            .sessionId(sessionId)
            .threadId(threadId)
            .status(IssueRunStatus.RUNNING)
            .startEntryId(startEntryId)
            .observedActivitySequence(issue.getNextActivitySequence() - 1)
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
      throw new ProjectVersionConflictException(
          "issue", Long.toString(issue.getVersion()), Long.toString(issue.getVersion()));
    }
    issueWorkStore.requestWork(issueId, Duration.ZERO);
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

  @Override
  public IssueRun getRun(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    return requireRun(runId);
  }

  @Override
  public IssueRun getActiveRun(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    IssueRun run = issueRunRepository.getActiveByIssueId(issueId);
    if (run == null) {
      throw new ProjectNotFoundException("issue_run");
    }
    return run;
  }

  @Override
  public IssueRun getLatestRun(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    IssueRun run = issueRunRepository.getLatestByIssueId(issueId);
    if (run == null) {
      throw new ProjectNotFoundException("issue_run");
    }
    return run;
  }

  @Override
  public List<IssueRun> listRuns(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueRunRepository.listByIssueId(issueId);
  }

  @Override
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
      throw new ProjectValidationException(
          "issue_run", "Cannot wait: run has no execution budget left");
    }
    run.setStatus(IssueRunStatus.WAITING);
    run.setActiveSince(null);
    updateRun(run, expectedVersion);
    return issueRunRepository.getById(runId);
  }

  @Override
  @Transactional
  public IssueRun resumeRun(UUID runId, long expectedVersion) {
    RunLock locked = lockRun(runId);
    IssueRun run = locked.run();
    if (run.getStatus() == IssueRunStatus.RUNNING) {
      // 已经是执行状态：不重复写行，也不再校验调用方版本。
      return issueRunRepository.getById(runId);
    }
    if (run.getStatus() != IssueRunStatus.WAITING) {
      throw new ProjectValidationException("issue_run", "Only a WAITING run can be resumed");
    }
    requireVersion(run, expectedVersion);
    if (locked.issue().isGateClosed()) {
      throw new ProjectValidationException(
          "issue_run", "Cannot resume a run while the issue gate is closed");
    }
    if (run.getRemainingExecutionMs() <= 0) {
      throw new ProjectValidationException(
          "issue_run", "Cannot resume: run has no execution budget left");
    }
    // 恢复消耗的是本次 Run 已接受的活动额度，不新建 Run、不重查阶段额度。
    run.setStatus(IssueRunStatus.RUNNING);
    run.setActiveSince(Instant.now());
    updateRun(run, expectedVersion);
    return issueRunRepository.getById(runId);
  }

  @Override
  @Transactional
  public IssueRun completeRun(
      UUID runId,
      long expectedVersion,
      String requestKey,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      String nextState) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(endEntryId, "endEntryId");
    String key = requireRequestKey(requestKey);
    boolean handoff = nextState != null;
    Identity identity =
        handoff
            ? IssueActivityIdempotency.identity(
                IssueActivityKind.STATE_CHANGE,
                "HANDOFF",
                key,
                runId,
                endEntryId,
                finalAnswerEntryId,
                nextState)
            : IssueActivityIdempotency.identity(
                IssueActivityKind.CONTROL,
                "COMPLETE_RUN",
                key,
                runId,
                endEntryId,
                finalAnswerEntryId);
    if (replayed(runId, identity)) {
      return currentRun(runId);
    }
    RunLock locked = lockRun(runId);
    if (replayedUnderLock(locked, identity)) {
      return locked.run();
    }
    IssueRun run = locked.run();
    requireActive(run);
    requireVersion(run, expectedVersion);
    requireWithinRunInterval(
        entryPath(run.getThreadId()), run.getStartEntryId(), endEntryId, finalAnswerEntryId, true);
    Issue issue = locked.issue();
    if (handoff) {
      ProjectWorkflow workflow = workflowCodec.decode(locked.project().getWorkflowJson());
      ProjectStateCode from = ProjectStateCode.of(run.getState());
      ProjectStateCode to = ProjectStateCode.of(nextState);
      ProjectValidationUtils.validate(
          "issue", () -> new IssueStateTransitions(workflow).requireTransition(from, to));
      ObjectNode data = objectMapper.createObjectNode();
      data.put("action", "HANDOFF");
      data.put("from", from.value());
      data.put("to", to.value());
      appendActivity(issue, identity, IssueActivityActorType.SYSTEM, null, null, null, data);
      issue.setState(to.value());
    } else {
      ObjectNode data = objectMapper.createObjectNode();
      data.put("action", "COMPLETE_RUN");
      appendActivity(issue, identity, IssueActivityActorType.SYSTEM, null, runId, null, data);
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
    closeRunScope(run);
    return issueRunRepository.getById(runId);
  }

  @Override
  @Transactional
  public IssueRun cancelRun(UUID runId, long expectedVersion, String requestKey, UUID endEntryId) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(endEntryId, "endEntryId");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL,
            "CANCEL_RUN",
            key,
            runId,
            endEntryId,
            PauseReason.USER.name());
    if (replayed(runId, identity)) {
      return currentRun(runId);
    }
    RunLock locked = lockRun(runId);
    if (replayedUnderLock(locked, identity)) {
      return locked.run();
    }
    IssueRun run = locked.run();
    requireActive(run);
    requireVersion(run, expectedVersion);
    requireWithinRunInterval(
        entryPath(run.getThreadId()), run.getStartEntryId(), endEntryId, null, false);
    terminate(run, IssueRunStatus.CANCELLED, endEntryId, null, expectedVersion);
    applyPauseGate(locked, identity, PauseReason.USER, "Run was stopped by a human");
    return issueRunRepository.getById(runId);
  }

  @Override
  @Transactional
  public IssueRun failRun(
      UUID runId, long expectedVersion, String requestKey, UUID endEntryId, String error) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(endEntryId, "endEntryId");
    String key = requireRequestKey(requestKey);
    String validError =
        ProjectValidationUtils.requireUtf8Text(
            error, "error", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "FAIL_RUN", key, runId, endEntryId, validError);
    if (replayed(runId, identity)) {
      return currentRun(runId);
    }
    RunLock locked = lockRun(runId);
    if (replayedUnderLock(locked, identity)) {
      return locked.run();
    }
    IssueRun run = locked.run();
    requireActive(run);
    requireVersion(run, expectedVersion);
    requireWithinRunInterval(
        entryPath(run.getThreadId()), run.getStartEntryId(), endEntryId, null, false);
    terminate(run, IssueRunStatus.FAILED, endEntryId, validError, expectedVersion);
    applyPauseGate(locked, identity, PauseReason.ERROR, validError);
    return issueRunRepository.getById(runId);
  }

  @Override
  @Transactional
  public IssueRun markUnknown(
      UUID runId, long expectedVersion, String requestKey, UUID endEntryId, String error) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(endEntryId, "endEntryId");
    String key = requireRequestKey(requestKey);
    String validError =
        ProjectValidationUtils.requireUtf8Text(
            error, "error", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "MARK_UNKNOWN", key, runId, endEntryId, validError);
    if (replayed(runId, identity)) {
      return currentRun(runId);
    }
    RunLock locked = lockRun(runId);
    if (replayedUnderLock(locked, identity)) {
      return locked.run();
    }
    IssueRun run = locked.run();
    requireActive(run);
    requireVersion(run, expectedVersion);
    requireWithinRunInterval(
        entryPath(run.getThreadId()), run.getStartEntryId(), endEntryId, null, false);
    terminate(run, IssueRunStatus.UNKNOWN, endEntryId, validError, expectedVersion);
    applyPauseGate(locked, identity, PauseReason.UNKNOWN, validError);
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
    closeRunScope(run);
  }

  /**
   * 在本 Run 进入终态的业务锁内闭合 branch scope：读取接受该 Run 时冻结的 SET_CONTRIBUTOR_STATE 源命令，翻成同一 {@code runId} 的
   * {@code active=false} 副本，并以纯设置批次写回同一 Thread——不产生用户消息、也不唤醒模型。
   *
   * <p>因为同一 Thread 的命令按 sequence 有序，新 Run 的 {@code active=true} 快照必然排在旧 Run 的 {@code false} 之后；旧
   * Run 收尾必须持有活动 Run 锁，因此不可能在关闭后覆盖新 Run 的 scope。
   */
  private void closeRunScope(IssueRun run) {
    HarnessRuntime runtime = requireRuntime();
    UUID threadId = run.getThreadId();
    ThreadCommand source =
        runtime
            .findThreadCommand(threadId, commandKey(run.getId(), "contributor-state"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "frozen run scope command is missing for run " + run.getId()));
    if (!(source.payload() instanceof SetContributorStateCommandPayload frozen)) {
      throw new IllegalStateException("run scope source command is not SET_CONTRIBUTOR_STATE");
    }
    ProjectRunScope scope = RUN_SCOPE_CODEC.decode(frozen.state().dataJson());
    if (!scope.runId().equals(run.getId()) || !scope.sourceThreadId().equals(threadId)) {
      throw new IllegalStateException("frozen run scope does not match the closing run");
    }
    NewThreadCommand closeCommand =
        new NewThreadCommand(
            new SetContributorStateCommandPayload(
                new CustomEntryPayload(
                    ProjectRunScope.CONTRIBUTOR_ID,
                    ProjectRunScope.CUSTOM_TYPE,
                    ProjectRunScope.SCHEMA_VERSION,
                    RUN_SCOPE_CODEC.encode(scope.closed()))),
            commandKey(run.getId(), "scope-closed"));
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                threadId, snapshot.thread().headEntryId(), snapshot.thread().nextCommandSequence()),
            List.of(closeCommand)),
        AcceptancePreflight.IDENTITY);
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

  /**
   * 锁前快速路径：命中同键同指纹时跳过版本、状态与区间校验。
   *
   * <p>未命中不能作为最终结论。并发首次提交可能落在本次检查与 owner 锁之间，调用方必须持锁后再查一次。
   */
  private boolean replayed(UUID runId, Identity identity) {
    IssueRun peek = requireRun(runId);
    return IssueActivityIdempotency.findApplied(
            issueActivityRepository, peek.getIssueId(), identity)
        != null;
  }

  /** owner 锁内的精确重试判定：同键同指纹返回当前 Run，同键异指纹冲突，未命中才继续版本与状态校验。 */
  private boolean replayedUnderLock(RunLock locked, Identity identity) {
    return IssueActivityIdempotency.findAppliedUnderLock(
            issueActivityRepository, locked.issue().getId(), identity)
        != null;
  }

  private void requireActive(IssueRun run) {
    if (!run.isActive()) {
      throw new ProjectValidationException("issue_run", "Run is already terminal");
    }
  }

  private void requireVersion(IssueRun run, long expectedVersion) {
    if (run.getVersion() != expectedVersion) {
      throw new ProjectVersionConflictException(
          "issue_run", Long.toString(expectedVersion), Long.toString(run.getVersion()));
    }
  }

  private void updateRun(IssueRun run, long expectedVersion) {
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue_run", Long.toString(expectedVersion), Long.toString(run.getVersion()));
    }
  }

  private void persistIssue(Issue issue, long expectedVersion) {
    if (!issueRepository.updateById(issue, expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(issue.getVersion()));
    }
  }

  /**
   * 读取该 Thread 的 root-to-head 历史路径（设计 §4.5 区间解释依据）。
   *
   * <p>只读快照，不产生锁；部署缺少 Harness Runtime 时确定失败，绝不退化为"无区间校验"的接受。
   */
  private EntryPath entryPath(UUID threadId) {
    return requireRuntime().getThreadSnapshot(threadId).entryPath();
  }

  /**
   * 复验 {@code (startEntryId,endEntryId]} 落在该 Thread 的真实历史路径上：end 必须是 start 的后代，final answer
   * 必须落在同一区间内。
   *
   * <p>外键只保证 Entry 属于同一 Session，因此必须沿父链判定，绝不能按时间排序或只看区间序号。{@code requireProgress} 为 true 时要求区间非空
   * （正常收尾必须真的产出过历史），失败/取消/不明允许空区间：接受之后还没有产出任何 Entry 时也必须能安全收尾。
   */
  private static void requireWithinRunInterval(
      EntryPath path,
      UUID startEntryId,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      boolean requireProgress) {
    List<UUID> entryIds = path.entries().stream().map(Entry::id).toList();
    int start = entryIds.indexOf(startEntryId);
    if (start < 0) {
      throw new ProjectValidationException(
          "issue_run", "Run start entry is not on the bounded thread history path");
    }
    int end = entryIds.indexOf(endEntryId);
    if (end < start || (requireProgress && end == start)) {
      throw new ProjectValidationException(
          "issue_run", "Run end entry must be a descendant of the run start entry");
    }
    if (finalAnswerEntryId != null) {
      int answer = entryIds.indexOf(finalAnswerEntryId);
      if (answer <= start || answer > end) {
        throw new ProjectValidationException(
            "issue_run", "Run final answer must be inside the run interval (start, end]");
      }
    }
  }

  private RunLock lockRun(UUID runId) {
    IssueRun peek = requireRun(runId);
    Issue peekIssue = issueRepository.getById(peek.getIssueId());
    if (peekIssue == null) {
      throw new ProjectNotFoundException("issue");
    }
    Project project = projectRepository.lockForKeyShare(peekIssue.getProjectId());
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    Issue issue = issueRepository.lockById(peekIssue.getId());
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new ProjectNotFoundException("issue_run");
    }
    return new RunLock(run, issue, issue.getVersion(), project);
  }

  /**
   * 锁前重放返回的权威当前 Run：在观察到 receipt 之后重新读取已提交的行，绕过事务内 MyBatis 一级缓存。
   *
   * <p>本事务早先读到的旧 Run 只是历史快照，不得作为重放结果返回：并发首次收尾完全可能落在那次读取与本事务的 receipt 检查之间。
   */
  private IssueRun currentRun(UUID runId) {
    IssueRun run = issueRunRepository.getByIdAuthoritative(runId);
    if (run == null) {
      throw new ProjectNotFoundException("issue_run");
    }
    return run;
  }

  private IssueRun requireRun(UUID runId) {
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw new ProjectNotFoundException("issue_run");
    }
    return run;
  }

  /**
   * 冻结本次 Run 的权威上下文快照：当前 Issue、阶段职责与业务执行身份（sourceThreadId）一起写入 branch custom state， 之后的模型 turn
   * 只读取这份快照，运行时不再反查 Thread 属于哪个 Issue。
   */
  private ProjectRunScope runScope(
      Issue issue, ProjectWorkflowState stage, String agentName, UUID runId, UUID threadId) {
    return new ProjectRunScope(
        runId,
        issue.getId(),
        issue.getProjectId(),
        threadId,
        issue.getNumber(),
        issue.getTitle(),
        issue.getDescription(),
        stage.state().value(),
        stage.name(),
        stage.instructions(),
        stage.next().stream().map(ProjectStateCode::value).toList(),
        agentName);
  }

  /**
   * 每次 Run 显式提交的完整命令批：先按固定顺序设置 Agent/Model/Environment 并冻结本次 Run 的 contributor state， 最后以恰一条
   * CUSTOM_MESSAGE（可信调用方输入，而非人类 USER_MESSAGE）启动任务。
   *
   * <p>Environment 取当前 stage 的显式选择（含 null 清除），而不是 Agent 默认 settings 的 environmentName，否则工作流里配置的
   * stage 环境会被 Agent 默认值悄悄覆盖；Agent 与 Model 仍以本 Run 明确物化的 run 配置为准。
   */
  private List<NewThreadCommand> buildRunCommands(
      BranchSettings settings, ProjectRunScope scope, String environmentName) {
    UUID runId = scope.runId();
    return List.of(
        command(new SetAgentCommandPayload(settings.agentName()), runId, "agent"),
        command(new SetModelCommandPayload(settings.model()), runId, "model"),
        command(new SetEnvironmentCommandPayload(environmentName), runId, "environment"),
        command(
            new SetContributorStateCommandPayload(
                new CustomEntryPayload(
                    ProjectRunScope.CONTRIBUTOR_ID,
                    ProjectRunScope.CUSTOM_TYPE,
                    ProjectRunScope.SCHEMA_VERSION,
                    RUN_SCOPE_CODEC.encode(scope))),
            runId,
            "contributor-state"),
        command(new CustomMessageCommandPayload(taskInput(scope)), runId, "task"));
  }

  private static NewThreadCommand command(ThreadCommandPayload payload, UUID runId, String role) {
    return new NewThreadCommand(payload, commandKey(runId, role));
  }

  /** 命令幂等键由 Run id 与角色确定：同一 Run 的重复接受不会重复入队。 */
  private static UUID commandKey(UUID runId, String role) {
    return UUID.nameUUIDFromBytes(
        ("issue-run:" + runId + ":" + role).getBytes(StandardCharsets.UTF_8));
  }

  /** 自动任务输入：显式指向系统指令中冻结的 Issue 上下文，不复制可变业务事实。 */
  private static AgentMessage taskInput(ProjectRunScope scope) {
    String text =
        "Start or continue the current Issue run for stage "
            + scope.stage()
            + " ("
            + scope.stageName()
            + "). Perform the work required by the current stage according to the authoritative"
            + " Issue context in the system instructions, and request a handoff only when the"
            + " stage work is complete.";
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
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
            .data(writeJson(data))
            .idempotencyKey(identity.key())
            .requestHash(identity.requestHash())
            .build();
    if (!issueActivityRepository.insert(activity)) {
      throw new IllegalStateException("failed to insert issue activity");
    }
    issue.setNextActivitySequence(sequence + 1);
  }

  private String writeJson(ObjectNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("failed to encode activity data", error);
    }
  }

  private static String requireRequestKey(String requestKey) {
    return ProjectValidationUtils.requireDisplayName(
        requestKey, "requestKey", MAX_REQUEST_KEY_LENGTH);
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }

  private record RunLock(IssueRun run, Issue issue, long issueVersion, Project project) {}
}
