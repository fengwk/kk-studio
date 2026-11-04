package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
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
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.ReceiptLock;
import fun.fengwk.kkstudio.work.DurableWorkStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 生命周期用例：归档/恢复归档、阻断/恢复、暂停/继续、停止、删除与状态迁移。
 *
 * <p>写路径复用 {@link IssueMutationSupport} 的 owner 锁与幂等 receipt；状态合法性由领域 {@link IssueStateTransitions}
 * 判定。停止时在途副作用结果不明，按 FAILED 收尾并保留 ERROR 暂停，原因是结果未知。
 */
@Service
@AllArgsConstructor
public class IssueLifecycleService {

  /** 在途副作用结果不明时停止 Run 的失败原因：明确声明结果未知，绝不声称失败原因已确认。 */
  private static final String OUTCOME_UNKNOWN_FAILURE =
      "Run stopped with in-flight side effects; outcome unknown";

  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final DurableWorkStore issueWorkQueue;
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
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

  /** 控制暂停：阻止新派发并记录原因与详情（USER/ERROR）。 */
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
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

  /**
   * 人工终止 Issue 执行：空闲时落 USER 暂停门禁，已有 ERROR 不被覆盖；活动 Run 收尾为 CANCELLED 并保留 USER 暂停；在途副作用不明时按 FAILED
   * 收尾并保留 ERROR 暂停，原因声明结果未知。
   *
   * <p>STOP receipt 与本次收尾在同一事务、同一次 Issue 行版本推进中落库，因此重复请求在任一路径都精确回放：既不会再次收尾 Run，也不会把首次收尾写入的 ERROR
   * 暂停改写成 USER。
   */
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    boolean outcomeUnknown = false;
    UUID stopHead = null;
    if (activeRun != null) {
      IssueThreadExecution port = threadExecutions.getIfAvailable();
      if (port == null) {
        throw new IllegalStateException("issue thread execution is not available");
      }
      IssueThreadView view = port.readThread(activeRun.getThreadId());
      outcomeUnknown = isUndeterminedInFlight(view);
      UUID stopRequestId =
          UUID.nameUUIDFromBytes(
              ("issue-run-user-stop:" + activeRun.getId()).getBytes(StandardCharsets.UTF_8));
      stopHead = port.stop(new StopCommand(activeRun.getThreadId(), stopRequestId, view.version()));
    }
    // 先在 Activity 流落下 STOP 事实并推进 Issue 行版本：Run 收尾（CANCEL/FAIL + PAUSE 活动）随后沿用这次推进后的行，
    // 既不会与收尾抢同一个 activity sequence，也不会产生第二次版本 CAS。
    boolean preserveError =
        activeRun == null && PauseReason.ERROR.name().equals(locked.issue().getPauseReason());
    appendStopReceipt(
        locked.issue(),
        identity,
        outcomeUnknown || preserveError ? PauseReason.ERROR : PauseReason.USER);
    if (activeRun == null) {
      if (!preserveError) {
        locked.issue().setPauseReason(PauseReason.USER.name());
        locked.issue().setPauseDetail(validDetail);
      }
      return mutationSupport.persist(locked, expectedVersion);
    }
    mutationSupport.persist(locked, expectedVersion);
    if (outcomeUnknown) {
      // 在途副作用无法安全判定：按失败收尾并保留 ERROR 暂停，原因明确声明结果未知；不自动重试，需显式 resumeIssue。
      issueRunService.finishRun(
          activeRun.getId(),
          activeRun.getVersion(),
          key + ":unknown",
          IssueRunStatus.FAILED,
          stopHead,
          null,
          null,
          OUTCOME_UNKNOWN_FAILURE);
      return mutationSupport.currentIssue(issueId);
    }
    issueRunService.finishRun(
        activeRun.getId(),
        activeRun.getVersion(),
        key + ":cancel",
        IssueRunStatus.CANCELLED,
        stopHead,
        null,
        null,
        null);
    return mutationSupport.currentIssue(issueId);
  }

  /** 记录人工停止意图：本次停止最终落在哪个暂停原因由收尾结果决定，数据里保留同一判定。 */
  private void appendStopReceipt(Issue issue, Identity identity, PauseReason reason) {
    ObjectNode data = mutationSupport.newData();
    data.put("action", "STOP");
    data.put("reason", reason.name());
    mutationSupport.appendActivity(
        issue, identity, IssueActivityActorType.HUMAN, null, null, null, data);
  }

  /** 深删除 Issue：严格要求无活动 Run，按依赖顺序清理各表与 Harness Session。 */
  @Transactional
  public void deleteIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    IssueMutationSupport.Locked locked = mutationSupport.lock(issueId, expectedVersion, true);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue", "Cannot delete an issue while it has an active run; stop or settle it first");
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue",
          "Cannot transition an issue while it has an active run; stop or settle it first");
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
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return receipt.locked() == null
          ? mutationSupport.currentIssue(issueId)
          : receipt.locked().issue();
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "issue", "Cannot reopen an issue while it has an active run; stop or settle it first");
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
   * 停止必须在「执行尚未收敛」时按结果未知失败收尾，而不是直接取消：本地仍有工作（命令 / Model / 工具 / owned continuation） 或已有结果未定的 UNKNOWN
   * 工具都表示外部状态无法安全判定。事实由 Runtime 统一投影，Harness 侧 UNKNOWN 语义不变。
   */
  private static boolean isUndeterminedInFlight(IssueThreadView view) {
    ThreadExecutionFacts facts = view.executionFacts();
    return facts.hasWork() || facts.hasUnknownToolOutcome();
  }
}
