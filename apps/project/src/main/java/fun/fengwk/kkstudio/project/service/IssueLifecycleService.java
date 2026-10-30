package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.project.domain.IssueStateTransitions;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 生命周期用例：归档/恢复归档、阻断/恢复、暂停/继续、停止、UNKNOWN 消解、删除与状态迁移。
 *
 * <p>写路径复用 {@link IssueMutationSupport} 的 owner 锁与幂等 receipt；状态合法性由领域 {@link IssueStateTransitions}
 * 判定。停止在外部执行 未收敛时收口为 UNKNOWN，而不是直接取消。
 */
@Service
@AllArgsConstructor
public class IssueLifecycleService {

  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final DurableWorkQueue issueWorkQueue;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;
  private final SessionDeletionOrchestrator sessionDeletion;
  private final ObjectProvider<IssueThreadExecution> threadExecutions;
  private final IssueMutationSupport mutationSupport;

  /** 归档：要求没有活动 Run；归档后禁止执行，不删除历史与资源。 */
  @Transactional
  public Issue archiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    // 允许读取已归档事实：当前版本的重复归档必须能回到「归档已是当前事实」的分支，而不是被归档门禁拒绝。
    IssueMutationSupport.Locked locked = mutationSupport.lock(issueId, expectedVersion, true);
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
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 恢复归档：只回到可编辑状态，不自动新建 Run。 */
  @Transactional
  public Issue unarchiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    IssueMutationSupport.Locked locked = mutationSupport.lock(issueId, expectedVersion, true);
    if (!locked.issue().isArchived()) {
      // 当前版本已经可编辑：不重复写行。陈旧版本在加锁时已被拒绝。
      return locked.issue();
    }
    locked.issue().setArchivedAt(null);
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 业务阻塞：原子记录 BLOCKED、原阶段与原因。 */
  @Transactional
  public Issue blockIssue(UUID issueId, long expectedVersion, String requestKey, String reason) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validReason =
        ProjectValidationUtils.requireUtf8Text(
            reason, "reason", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "BLOCK", key, validReason);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot block an issue with an unresolved UNKNOWN gate; resolve unknown verification first");
    }
    ProjectStateCode from = ProjectStateCode.of(locked.issue().getState());
    ProjectValidationUtils.validate(
        "issue",
        () -> new IssueStateTransitions(locked.workflow()).requireBlock(from, validReason));
    ObjectNode data = mutationSupport.newData();
    data.put("action", "BLOCK");
    data.put("from", locked.issue().getState());
    data.put("to", ProjectWorkflowReservedState.BLOCKED.code().value());
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setState(ProjectWorkflowReservedState.BLOCKED.code().value());
    locked.issue().setBlockedFromState(from.value());
    locked.issue().setBlockReason(validReason);
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 恢复业务阻塞：显式回到阻塞前阶段。 */
  @Transactional
  public Issue recoverIssue(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RECOVER", key);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
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
    ObjectNode data = mutationSupport.newData();
    data.put("action", "RECOVER");
    data.put("from", issue.getState());
    data.put("to", recovered.value());
    mutationSupport.appendActivity(
        issue, identity, IssueActivityActorType.HUMAN, null, null, null, data);
    issue.setState(recovered.value());
    issue.setBlockedFromState(null);
    issue.setBlockReason(null);
    Issue persisted = mutationSupport.persist(locked, expectedVersion);
    // 恢复到仍需要执行的工作阶段时唤醒 mailbox；已有活动 Run 由该 Run 自己收尾。
    mutationSupport.wakeIfExecutable(persisted, locked.workflow());
    return persisted;
  }

  /** 控制暂停：阻止新派发并记录原因与详情（USER/ERROR/UNKNOWN）。 */
  @Transactional
  public Issue pauseIssue(
      UUID issueId, long expectedVersion, String requestKey, PauseReason reason, String detail) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(reason, "reason");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validDetail =
        ProjectValidationUtils.requireUtf8Text(
            detail, "detail", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "PAUSE", key, reason.name(), validDetail);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    ObjectNode data = mutationSupport.newData();
    data.put("action", "PAUSE");
    data.put("reason", reason.name());
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.SYSTEM, null, null, null, data);
    locked.issue().setPauseReason(reason.name());
    locked.issue().setPauseDetail(validDetail);
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 显式解除控制暂停门禁。 */
  @Transactional
  public Issue resumeIssue(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity = IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RESUME", key);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue",
          "Cannot resume an issue with unresolved UNKNOWN gate; resolve unknown verification first");
    }
    ObjectNode data = mutationSupport.newData();
    data.put("action", "RESUME");
    data.put("reason", locked.issue().getPauseReason());
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setPauseReason(null);
    locked.issue().setPauseDetail(null);
    Issue persisted = mutationSupport.persist(locked, expectedVersion);
    // 门禁解除后重新登记 Work；额度限制新建 Run，但不限制已接受 Run 的恢复。
    mutationSupport.wakeIfExecutable(persisted, locked.workflow());
    return persisted;
  }

  /** 人工终止 Issue 执行：活动 Run 收尾为 CANCELLED 并保留 USER 暂停门禁；在途副作用不明则进入 UNKNOWN。 */
  @Transactional
  public Issue stopIssue(UUID issueId, long expectedVersion, String requestKey, String detail) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validDetail =
        detail == null || detail.isBlank()
            ? "Stopped by user"
            : ProjectValidationUtils.requireUtf8Text(
                detail, "detail", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "STOP", key, validDetail);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun == null) {
      ObjectNode data = mutationSupport.newData();
      data.put("action", "STOP");
      data.put("reason", PauseReason.USER.name());
      mutationSupport.appendActivity(
          locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
      locked.issue().setPauseReason(PauseReason.USER.name());
      locked.issue().setPauseDetail(validDetail);
      return mutationSupport.persist(locked, expectedVersion);
    }
    IssueThreadExecution port = threadExecutions != null ? threadExecutions.getIfAvailable() : null;
    IssueThreadView view = port != null ? port.readThread(activeRun.getThreadId()) : null;
    if (isUndeterminedInFlight(view)) {
      issueRunService.markUnknown(
          activeRun.getId(),
          activeRun.getVersion(),
          key + ":unknown",
          view.headEntryId(),
          validDetail);
      return mutationSupport.currentIssue(issueId);
    }
    UUID endId = view != null ? view.headEntryId() : activeRun.getStartEntryId();
    issueRunService.cancelRun(activeRun.getId(), activeRun.getVersion(), key + ":cancel", endId);
    return mutationSupport.currentIssue(issueId);
  }

  /** 记录人工核查依据并解除 UNKNOWN 门禁（转为 USER 暂停门禁，需显式 resumeIssue 唤醒）。 */
  @Transactional
  public Issue resolveUnknown(
      UUID issueId, long expectedVersion, String requestKey, String verification) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validVerification =
        ProjectValidationUtils.requireUtf8Text(
            verification, "verification", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "RESOLVE_UNKNOWN", key, validVerification);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (!PauseReason.UNKNOWN.name().equals(locked.issue().getPauseReason())) {
      throw new ProjectValidationException(
          "issue", "Issue does not carry an unresolved UNKNOWN gate");
    }
    ObjectNode data = mutationSupport.newData();
    data.put("action", "RESOLVE_UNKNOWN");
    data.put("verification", validVerification);
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    locked.issue().setPauseReason(PauseReason.USER.name());
    locked.issue().setPauseDetail(validVerification);
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 深删除 Issue：严格要求无活动 Run 且无未核查的 UNKNOWN，按依赖顺序清理各表与 Harness Session。 */
  @Transactional
  public void deleteIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    IssueMutationSupport.Locked locked = mutationSupport.lock(issueId, expectedVersion, true);
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
    issueWorkQueue.delete(issueId);
    issueRunRepository.deleteByIssueId(issueId);
    stageBudgetRepository.deleteByIssueId(issueId);
    List<IssueAgentThread> bindings = issueAgentThreadRepository.listByIssueId(issueId);
    for (IssueAgentThread binding : bindings) {
      sessionDeletion.deleteSessionsByOwner(new OwnerRef.IssueAgent(issueId, binding.agentName()));
    }
    if (!issueRepository.deleteById(issueId, expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
  }

  /** 正常转移：目标是当前阶段 workflow {@code next} 白名单成员且处于启用状态。 */
  @Transactional
  public Issue transition(UUID issueId, long expectedVersion, String requestKey, String toState) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(toState, "toState");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.STATE_CHANGE, "TRANSITION", key, toState);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
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
    mutationSupport.appendStateChange(
        locked.issue(), identity, "TRANSITION", from.value(), to.value());
    locked.issue().setState(to.value());
    Issue persisted = mutationSupport.persist(locked, expectedVersion);
    // 进入有 Agent 的工作阶段时登记 Work；保留阶段与人工阶段不产生执行。
    mutationSupport.wakeIfExecutable(persisted, locked.workflow());
    return persisted;
  }

  /** DONE 重开：显式回到 INIT。 */
  @Transactional
  public Issue reopen(UUID issueId, long expectedVersion, String requestKey) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.STATE_CHANGE, "REOPEN", key);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
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
    mutationSupport.appendStateChange(locked.issue(), identity, "REOPEN", from.value(), to.value());
    locked.issue().setState(to.value());
    return mutationSupport.persist(locked, expectedVersion);
  }

  /**
   * 停止必须在「执行尚未收敛」时收敛为 UNKNOWN 而不是直接取消：本地仍有工作（命令 / Model / 工具 / owned continuation） 或已有结果未定的 UNKNOWN
   * 工具都表示外部状态无法安全判定。事实由 Runtime 统一投影，本类不再自行推导「在途」。
   */
  private static boolean isUndeterminedInFlight(IssueThreadView view) {
    if (view == null) {
      return false;
    }
    ThreadExecutionFacts facts = view.executionFacts();
    return facts.hasWork() || facts.hasUnknownToolOutcome();
  }
}
