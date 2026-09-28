package fun.fengwk.kkstudio.project.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.project.domain.IssueStageBudget;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
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
import fun.fengwk.kkstudio.project.port.IssueAgentSessionDeletionPort;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.project.service.impl.IssueActivityIdempotency.Identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 用例实现：所有写路径先按 Project SHARE → Issue UPDATE 加锁，再以版本 CAS 提交状态、门禁与分配游标。
 *
 * <p>合法的 Issue 写操作与对应 Activity 在同一事务保存（设计 §4.6）。每个动作按请求键与规范化请求指纹判定：锁前检查只是快速路径； 进入 owner 锁（Project
 * SHARE → Issue UPDATE）之后、版本与状态校验之前必须再查一次 receipt。同键同指纹是丢响应后的精确重试， 返回当前 Issue
 * 或当前额度而不重复应用；同键异指纹确定性冲突。因此并发重复请求不会在版本校验处误报冲突，也不会重复改变状态、门禁或额度。阶段额度直接复用领域 {@link IssueStageBudget}
 * 的授权/重置语义，不在 platform 复制一份额度规则。
 */
@Service
@AllArgsConstructor
public class IssueServiceImpl implements IssueService {

  private static final int MAX_REQUEST_KEY_LENGTH = 128;

  /** 单次活动窗口上限：读取面与投递面都只检视有界窗口，绝不无界加载整条事实流。 */
  private static final int MAX_ACTIVITY_LIMIT = 200;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueWorkRepository issueWorkRepository;
  private final IssueWorkStore issueWorkStore;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final ObjectMapper objectMapper;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;
  private final IssueAgentSessionDeletionPort sessionDeletionPort;
  private final ObjectProvider<HarnessRuntime> runtimes;

  @Override
  @Transactional
  public Issue createIssue(UUID projectId, String title, String description) {
    Objects.requireNonNull(projectId, "projectId");
    Project project = projectRepository.getById(projectId);
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new ProjectValidationException(
          "project", "Cannot create an issue in an archived project");
    }
    String validTitle =
        ProjectValidationUtils.requireDisplayName(
            title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH);
    String validDescription =
        ProjectValidationUtils.optionalUtf8Text(
            description == null ? "" : description,
            "description",
            ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    long number = projectRepository.allocateNextIssueNumber(projectId);
    Issue issue =
        Issue.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .number(number)
            .title(validTitle)
            .description(validDescription)
            .state(ProjectWorkflowReservedState.INIT.code().value())
            .nextRunOrdinal(1)
            .nextActivitySequence(1)
            .build();
    if (!issueRepository.create(issue)) {
      throw new IllegalStateException("failed to insert issue");
    }
    return issueRepository.getById(issue.getId());
  }

  @Override
  public Issue getIssue(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    return issue;
  }

  @Override
  public List<Issue> listIssues(UUID projectId, boolean archived) {
    Objects.requireNonNull(projectId, "projectId");
    return issueRepository.listByProjectIdAndArchived(projectId, archived);
  }

  @Override
  @Transactional
  public Issue updateIssue(UUID issueId, long expectedVersion, String title, String description) {
    Objects.requireNonNull(issueId, "issueId");
    Locked locked = lock(issueId, expectedVersion, false);
    locked
        .issue()
        .setTitle(
            ProjectValidationUtils.requireDisplayName(
                title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH));
    locked
        .issue()
        .setDescription(
            ProjectValidationUtils.optionalUtf8Text(
                description == null ? "" : description,
                "description",
                ProjectValidationUtils.MAX_LARGE_TEXT_BYTES));
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Issue archiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    // 允许读取已归档事实：当前版本的重复归档必须能回到「归档已是当前事实」的分支，而不是被归档门禁拒绝。
    Locked locked = lock(issueId, expectedVersion, true);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue", "Cannot archive an issue while it has an active run; stop or settle it first");
    }
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot archive an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    if (locked.issue().isArchived()) {
      // 当前版本已经归档：不重复写行。陈旧版本在加锁时已被拒绝。
      return locked.issue();
    }
    locked.issue().setArchivedAt(Instant.now());
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Issue unarchiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Locked locked = lock(issueId, expectedVersion, true);
    if (!locked.issue().isArchived()) {
      // 当前版本已经可编辑：不重复写行。陈旧版本在加锁时已被拒绝。
      return locked.issue();
    }
    locked.issue().setArchivedAt(null);
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Issue appendComment(UUID issueId, long expectedVersion, String requestKey, String body) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    String validBody =
        ProjectValidationUtils.requireUtf8Text(
            body, "body", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.COMMENT, "COMMENT", key, validBody);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    appendActivity(
        locked.issue(),
        identity,
        IssueActivityActorType.HUMAN,
        null,
        null,
        validBody,
        objectMapper.createObjectNode());
    persist(locked, expectedVersion);
    // 普通评论不自动唤醒 Agent（设计 §4.6）。
    return issueRepository.getById(issueId);
  }

  @Override
  @Transactional
  public Issue appendInstruction(
      UUID issueId, long expectedVersion, String requestKey, String body) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    String validBody =
        ProjectValidationUtils.requireUtf8Text(
            body, "body", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.INSTRUCTION, "INSTRUCTION", key, validBody);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun == null) {
      // 没有活动 Run 时不构造隐藏的未来阶段消息队列：先明确启动或恢复。
      throw new ProjectValidationException(
          "issue",
          "Cannot deliver an instruction without an active run; start or resume the issue first");
    }
    appendActivity(
        locked.issue(),
        identity,
        IssueActivityActorType.HUMAN,
        null,
        activeRun.getId(),
        validBody,
        objectMapper.createObjectNode());
    persist(locked, expectedVersion);
    // 指示已持久接受，由 mailbox 在安全点投递给明确的当前 Run。
    issueWorkStore.requestWork(issueId, Instant.now());
    return issueRepository.getById(issueId);
  }

  @Override
  public List<IssueActivity> listActivities(UUID issueId, long afterSequence, int limit) {
    Objects.requireNonNull(issueId, "issueId");
    getIssue(issueId);
    if (afterSequence < 0) {
      throw new ProjectValidationException("afterSequence", "afterSequence must be non-negative");
    }
    return issueActivityRepository.listPage(issueId, afterSequence, clampLimit(limit));
  }

  @Override
  public List<IssueAgentThread> listAgentThreads(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    getIssue(issueId);
    return issueAgentThreadRepository.listByIssueId(issueId);
  }

  private static int clampLimit(int limit) {
    if (limit <= 0) {
      throw new ProjectValidationException("limit", "limit must be positive");
    }
    return Math.min(limit, MAX_ACTIVITY_LIMIT);
  }

  @Override
  @Transactional
  public Issue blockIssue(UUID issueId, long expectedVersion, String requestKey, String reason) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    String validReason =
        ProjectValidationUtils.requireUtf8Text(
            reason, "reason", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "BLOCK", key, validReason);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot block an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    ProjectStateCode from = ProjectStateCode.of(locked.issue().getState());
    ProjectValidationUtils.validate(
        "issue",
        () -> new IssueStateTransitions(locked.workflow()).requireBlock(from, validReason));
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "BLOCK");
    data.put("from", locked.issue().getState());
    data.put("to", ProjectWorkflowReservedState.BLOCKED.code().value());
    appendActivity(locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setState(ProjectWorkflowReservedState.BLOCKED.code().value());
    locked.issue().setBlockedFromState(from.value());
    locked.issue().setBlockReason(validReason);
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Issue recoverIssue(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RECOVER", key);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    Issue issue = locked.issue();
    if (!ProjectWorkflowReservedState.BLOCKED.code().value().equals(issue.getState())) {
      throw new ProjectValidationException("issue", "Issue is not blocked");
    }
    ProjectStateCode recovered =
        ProjectValidationUtils.resolve(
            "issue",
            () ->
                new IssueStateTransitions(locked.workflow())
                    .requireRecover(ProjectStateCode.of(issue.getBlockedFromState())));
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "RECOVER");
    data.put("from", issue.getState());
    data.put("to", recovered.value());
    appendActivity(issue, identity, IssueActivityActorType.HUMAN, null, null, null, data);
    issue.setState(recovered.value());
    issue.setBlockedFromState(null);
    issue.setBlockReason(null);
    Issue persisted = persist(locked, expectedVersion);
    // 恢复到仍需要执行的工作阶段时唤醒 mailbox；已有活动 Run 由该 Run 自己收尾。
    wakeIfExecutable(persisted, locked.project());
    return persisted;
  }

  @Override
  @Transactional
  public Issue pauseIssue(
      UUID issueId, long expectedVersion, String requestKey, PauseReason reason, String detail) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(reason, "reason");
    String key = requireRequestKey(requestKey);
    String validDetail =
        ProjectValidationUtils.requireUtf8Text(
            detail, "detail", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "PAUSE", key, reason.name(), validDetail);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "PAUSE");
    data.put("reason", reason.name());
    appendActivity(locked.issue(), identity, IssueActivityActorType.SYSTEM, null, null, null, data);
    locked.issue().setPauseReason(reason.name());
    locked.issue().setPauseDetail(validDetail);
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Issue resumeIssue(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    Identity identity = IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RESUME", key);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot resume an issue with unresolved UNKNOWN gate; resolve unknown verification first");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "RESUME");
    data.put("reason", locked.issue().getPauseReason());
    appendActivity(locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setPauseReason(null);
    locked.issue().setPauseDetail(null);
    Issue persisted = persist(locked, expectedVersion);
    // 门禁解除后重新登记 Work；额度限制新建 Run，但不限制已接受 Run 的恢复。
    wakeIfExecutable(persisted, locked.project());
    return persisted;
  }

  @Override
  @Transactional
  public Issue stopIssue(UUID issueId, long expectedVersion, String requestKey, String detail) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    String validDetail =
        detail == null || detail.isBlank()
            ? "Stopped by user"
            : ProjectValidationUtils.requireUtf8Text(
                detail, "detail", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "STOP", key, validDetail);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun == null) {
      ObjectNode data = objectMapper.createObjectNode();
      data.put("action", "STOP");
      data.put("reason", PauseReason.USER.name());
      appendActivity(
          locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
      locked.issue().setPauseReason(PauseReason.USER.name());
      locked.issue().setPauseDetail(validDetail);
      return persist(locked, expectedVersion);
    }
    HarnessRuntime runtime = runtimes != null ? runtimes.getIfAvailable() : null;
    ThreadSnapshot snapshot =
        runtime != null ? runtime.getThreadSnapshot(activeRun.getThreadId()) : null;
    if (snapshot != null && isUndeterminedInFlight(snapshot)) {
      UUID headId =
          snapshot.thread() != null ? snapshot.thread().headEntryId() : activeRun.getStartEntryId();
      issueRunService.markUnknown(
          activeRun.getId(), activeRun.getVersion(), key + ":unknown", headId, validDetail);
      return issueRepository.getById(issueId);
    }
    UUID endId =
        snapshot != null && snapshot.thread() != null
            ? snapshot.thread().headEntryId()
            : activeRun.getStartEntryId();
    issueRunService.cancelRun(activeRun.getId(), activeRun.getVersion(), key + ":cancel", endId);
    return issueRepository.getById(issueId);
  }

  @Override
  @Transactional
  public Issue resolveUnknown(
      UUID issueId, long expectedVersion, String requestKey, String verification) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    String validVerification =
        ProjectValidationUtils.requireUtf8Text(
            verification, "verification", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "RESOLVE_UNKNOWN", key, validVerification);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    if (!PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue", "Issue does not carry an unresolved UNKNOWN gate");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "RESOLVE_UNKNOWN");
    data.put("verification", validVerification);
    appendActivity(locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setPauseReason(PauseReason.USER.name());
    locked.issue().setPauseDetail(validVerification);
    return persist(locked, expectedVersion);
  }

  @Override
  @Transactional
  public void deleteIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Locked locked = lock(issueId, expectedVersion, true);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue", "Cannot delete an issue while it has an active run; stop or settle it first");
    }
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot delete an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    issueEvidenceService.releaseAll(issueId);
    issueActivityRepository.deleteByIssueId(issueId);
    issueWorkRepository.deleteByIssueId(issueId);
    issueRunRepository.deleteByIssueId(issueId);
    stageBudgetRepository.deleteByIssueId(issueId);
    List<IssueAgentThread> bindings = issueAgentThreadRepository.listByIssueId(issueId);
    for (IssueAgentThread binding : bindings) {
      sessionDeletionPort.deleteIssueAgentSessions(issueId, binding.agentName());
    }
    if (!issueRepository.deleteById(issueId, expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
  }

  @Override
  @Transactional
  public Issue transition(UUID issueId, long expectedVersion, String requestKey, String toState) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(toState, "toState");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.STATE_CHANGE, "TRANSITION", key, toState);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue",
          "Cannot transition an issue while it has an active run; stop or settle it first");
    }
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot transition an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    if (locked.issue().isPaused()) {
      throw new ProjectValidationException(
          "issue", "Cannot transition a paused issue; resume it first");
    }
    ProjectStateCode from = ProjectStateCode.of(locked.issue().getState());
    ProjectStateCode to = ProjectStateCode.of(toState);
    ProjectValidationUtils.validate(
        "issue", () -> new IssueStateTransitions(locked.workflow()).requireTransition(from, to));
    appendStateChange(locked.issue(), identity, "TRANSITION", from.value(), to.value());
    locked.issue().setState(to.value());
    Issue persisted = persist(locked, expectedVersion);
    // 进入有 Agent 的工作阶段时登记 Work；保留阶段与人工阶段不产生执行。
    wakeIfExecutable(persisted, locked.project());
    return persisted;
  }

  @Override
  @Transactional
  public Issue reopen(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.STATE_CHANGE, "REOPEN", key);
    if (replayed(issueId, identity)) {
      return getIssue(issueId);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return locked.issue();
    }
    requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue", "Cannot reopen an issue while it has an active run; stop or settle it first");
    }
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot reopen an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    if (locked.issue().isPaused()) {
      throw new ProjectValidationException(
          "issue", "Cannot reopen a paused issue; resume it first");
    }
    ProjectStateCode from = ProjectStateCode.of(locked.issue().getState());
    ProjectStateCode to =
        ProjectValidationUtils.resolve(
            "issue", () -> new IssueStateTransitions(locked.workflow()).requireReopen(from));
    appendStateChange(locked.issue(), identity, "REOPEN", from.value(), to.value());
    locked.issue().setState(to.value());
    return persist(locked, expectedVersion);
  }

  private static boolean isUndeterminedInFlight(ThreadSnapshot snapshot) {
    if (snapshot == null) {
      return false;
    }
    if (snapshot.queuedCommands() != null && !snapshot.queuedCommands().isEmpty()) {
      return true;
    }
    if (snapshot.model() != null
        && (snapshot.model().status() == null || !snapshot.model().status().isTerminal())) {
      return true;
    }
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      ToolInvocationStatus status = tool.status();
      if (status == null
          || status == ToolInvocationStatus.READY
          || status == ToolInvocationStatus.DISPATCHING
          || status == ToolInvocationStatus.RUNNING
          || status == ToolInvocationStatus.UNKNOWN) {
        return true;
      }
    }
    if (snapshot.entryPath() != null) {
      Entry head = snapshot.entryPath().head();
      if (head != null && head.payload() instanceof TurnEndPayload end && end.continueModel()) {
        return true;
      }
    }
    return false;
  }

  @Override
  @Transactional
  public StageBudgetView authorizeStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "AUTHORIZE", key, state, maxRuns);
    if (replayed(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    requireVersion(locked, expectedVersion);
    ProjectStateCode code = ProjectStateCode.of(state);
    IssueStageBudget authorized =
        ProjectValidationUtils.resolve(
            "stage_budget", () -> IssueStageBudget.authorize(locked.workflow(), code, maxRuns));
    if (stageBudgetRepository.get(issueId, state) != null) {
      throw new ProjectDuplicateException("stage_budget", "Stage budget is already authorized");
    }
    IssueStageBudgetRow budget =
        IssueStageBudgetRow.builder()
            .issueId(issueId)
            .state(state)
            .maxRuns(authorized.maxRuns())
            .budgetAfterOrdinal(authorized.budgetAfterOrdinal())
            .build();
    if (!stageBudgetRepository.insert(budget)) {
      throw new IllegalStateException("failed to insert stage budget");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "AUTHORIZE");
    data.put("state", state);
    data.put("maxRuns", authorized.maxRuns());
    data.put("budgetAfterOrdinal", authorized.budgetAfterOrdinal());
    appendActivity(locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    persist(locked, expectedVersion);
    return view(budget);
  }

  @Override
  @Transactional
  public StageBudgetView resetStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RESET", key, state, maxRuns);
    if (replayed(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    Locked locked = lockOwner(issueId, false);
    if (replayedUnderLock(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "stage_budget", "Cannot reset stage budget while an active run exists");
    }
    ProjectStateCode code = ProjectStateCode.of(state);
    IssueStageBudgetRow existing = stageBudgetRepository.get(issueId, state);
    long previousMax = existing != null ? existing.getMaxRuns() : 0;
    long previousAfter = existing != null ? existing.getBudgetAfterOrdinal() : 0;
    IssueStageBudget currentBudget =
        existing == null
            ? ProjectValidationUtils.resolve(
                "stage_budget", () -> IssueStageBudget.authorize(locked.workflow(), code, maxRuns))
            : new IssueStageBudget(code, existing.getMaxRuns(), existing.getBudgetAfterOrdinal());
    IssueStageBudget reset =
        ProjectValidationUtils.resolve(
            "stage_budget",
            () ->
                currentBudget.reset(
                    locked.workflow(), maxRuns, locked.issue().getNextRunOrdinal()));
    IssueStageBudgetRow budget =
        IssueStageBudgetRow.builder()
            .issueId(issueId)
            .state(state)
            .maxRuns(reset.maxRuns())
            .budgetAfterOrdinal(reset.budgetAfterOrdinal())
            .build();
    boolean applied =
        existing == null
            ? stageBudgetRepository.insert(budget)
            : stageBudgetRepository.update(budget);
    if (!applied) {
      throw new IllegalStateException("failed to reset stage budget");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", "RESET");
    data.put("state", state);
    data.put("previousMaxRuns", previousMax);
    data.put("maxRuns", reset.maxRuns());
    data.put("previousBudgetAfterOrdinal", previousAfter);
    data.put("budgetAfterOrdinal", reset.budgetAfterOrdinal());
    appendActivity(locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    persist(locked, expectedVersion);
    return view(budget);
  }

  @Override
  public StageBudgetView getStageBudget(UUID issueId, String state) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    IssueStageBudgetRow budget = stageBudgetRepository.get(issueId, state);
    if (budget == null) {
      throw new ProjectNotFoundException("stage_budget");
    }
    return view(budget);
  }

  private StageBudgetView view(IssueStageBudgetRow budget) {
    long used =
        issueRunRepository.countByIssueIdAndStateAfterOrdinal(
            budget.getIssueId(), budget.getState(), budget.getBudgetAfterOrdinal());
    long remaining = Math.max(0L, budget.getMaxRuns() - used);
    return new StageBudgetView(
        budget.getState(), budget.getMaxRuns(), budget.getBudgetAfterOrdinal(), used, remaining);
  }

  /**
   * 锁前快速路径：命中同键同指纹时跳过加锁与业务校验（请求字段校验仍然生效）。
   *
   * <p>未命中不能作为最终结论。并发的首次提交可能发生在本次检查之后、owner 锁之前，调用方必须在持锁后再次判定。
   */
  private boolean replayed(UUID issueId, Identity identity) {
    if (issueRepository.getById(issueId) == null) {
      throw new ProjectNotFoundException("issue");
    }
    return IssueActivityIdempotency.findApplied(issueActivityRepository, issueId, identity) != null;
  }

  /** owner 锁内的精确重试判定：同键同指纹返回当前事实，同键异指纹冲突，未命中才继续版本与状态校验。 */
  private boolean replayedUnderLock(UUID issueId, Identity identity) {
    return IssueActivityIdempotency.findAppliedUnderLock(issueActivityRepository, issueId, identity)
        != null;
  }

  private void appendStateChange(
      Issue issue, Identity identity, String action, String from, String to) {
    ObjectNode data = objectMapper.createObjectNode();
    data.put("action", action);
    data.put("from", from);
    data.put("to", to);
    appendActivity(issue, identity, IssueActivityActorType.SYSTEM, null, null, null, data);
  }

  /**
   * 追加一条活动（设计 §4.6 一个动作一条活动）：请求键与指纹来自调用方身份，正文与 Run 引用按动作填写。
   *
   * <p>调用方必须在 owner 锁内、版本与状态校验之前完成 {@link IssueActivityIdempotency#findApplied 精确重试判定}；本方法只在首次
   * 执行路径调用。写完活动后必须在同一事务内以版本 CAS 写回 Issue 行。
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

  /**
   * 以 Project FOR KEY SHARE → Issue FOR UPDATE 的固定锁序加锁，并做版本与归属校验。归档 Issue 默认拒绝写，恢复归档时显式放行。
   *
   * <p>没有活动 receipt 的写路径使用本方法。带请求键的路径必须先 {@link #lockOwner}，在锁内查 receipt，再做版本校验。
   */
  private Locked lock(UUID issueId, long expectedVersion, boolean allowArchivedIssue) {
    Locked locked = lockOwner(issueId, allowArchivedIssue);
    requireVersion(locked, expectedVersion);
    return locked;
  }

  /** 只取得 owner 锁与归属校验，把 receipt 与版本判定留给调用方按固定顺序完成。 */
  private Locked lockOwner(UUID issueId, boolean allowArchivedIssue) {
    Objects.requireNonNull(issueId, "issueId");
    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new ProjectNotFoundException("issue");
    }
    Project project = projectRepository.lockForKeyShare(initial.getProjectId());
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new ProjectValidationException(
          "project", "Cannot modify an issue in an archived project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(project.getId())) {
      throw new ProjectValidationException("issue", "Issue hierarchy is inconsistent");
    }
    if (issue.isArchived() && !allowArchivedIssue) {
      throw new ProjectValidationException("issue", "Cannot modify an archived issue");
    }
    return new Locked(issue, project, workflowCodec.decode(project.getWorkflowJson()));
  }

  private void requireVersion(Locked locked, long expectedVersion) {
    if (locked.issue().getVersion() != expectedVersion) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
  }

  /**
   * 状态或门禁变化后，若当前阶段是启用且有 Agent 的工作阶段就登记一次 Work。
   *
   * <p>mailbox 只表达「重新检查当前 Issue」，因此这里只登记事实，不预判额度或派发决定；保留阶段、人工阶段与暂停门禁都不产生 Work。
   */
  private void wakeIfExecutable(Issue issue, Project project) {
    if (issue.isPaused()) {
      return;
    }
    ProjectStateCode state = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return;
    }
    ProjectWorkflowState stage =
        workflowCodec.decode(project.getWorkflowJson()).find(state).orElse(null);
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return;
    }
    issueWorkStore.requestWork(issue.getId(), Instant.now());
  }

  private Issue persist(Locked locked, long expectedVersion) {
    if (!issueRepository.updateById(locked.issue(), expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
    return issueRepository.getById(locked.issue().getId());
  }

  private static String requireRequestKey(String requestKey) {
    return ProjectValidationUtils.requireDisplayName(
        requestKey, "requestKey", MAX_REQUEST_KEY_LENGTH);
  }

  private record Locked(Issue issue, Project project, ProjectWorkflow workflow) {}
}
