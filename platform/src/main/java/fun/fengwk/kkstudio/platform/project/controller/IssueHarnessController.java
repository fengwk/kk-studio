package fun.fengwk.kkstudio.platform.project.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueAgentSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue Controller 与 Harness 的边界。
 *
 * <p>本类只负责投影 Harness 状态、提交幂等命令、对齐 YOLO 和延迟停止 Session；Issue/Run 状态迁移由 {@link IssueReconciler} 负责。
 */
@Slf4j
@Service
class IssueHarnessController {

  private static final ThreadContextClassifier CONTEXT_CLASSIFIER = new ThreadContextClassifier();

  /** 引导/投递失败对外的稳定脱敏消息：reconcile 会整体回滚并按该失败重试。 */
  static final String HARNESS_BOOTSTRAP_FAILED = "Harness session bootstrap failed";

  /** Project 策略尚未在 Thread 上生效：不得继续按旧 YOLO 启动 Run。 */
  static final String YOLO_ALIGNMENT_FAILED = "Harness YOLO alignment failed";

  /** 复用工作 Branch 前仍有未收尾模型/工具调用或未消费命令。 */
  static final String HARNESS_BRANCH_UNSETTLED = "Harness branch is not quiescent";

  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final ProjectHarnessSessionBootstrapService bootstrapService;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private final ObjectProvider<HarnessRuntime> harnessRuntimes;

  IssueHarnessController(
      IssueAgentSessionRepository issueAgentSessionRepository,
      ProjectHarnessSessionBootstrapService bootstrapService,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
      ObjectProvider<HarnessRuntime> harnessRuntimes) {
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
    this.harnessRuntimes = Objects.requireNonNull(harnessRuntimes, "harnessRuntimes");
  }

  void bootstrap(Project project, Issue issue, IssueRun run) {
    String action = run.getRole() == IssueRunRole.EXECUTOR ? "Execute" : "Review";
    String initialMessage = action + " issue #" + issue.getNumber() + ": " + issue.getTitle();
    IssueAgentSession existing =
        issueAgentSessionRepository.findByIssueIdAndAgentName(issue.getId(), run.getAgentName());
    if (existing != null) {
      // (Issue, Agent) 的归属随 Issue 稳定：后续 Run 复用自己的 Session 与工作 Branch，绝不重建归属。
      deliverRunInitialMessage(project, existing, run, initialMessage);
      return;
    }
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    try {
      bootstrapService.bootstrapIssueAgentSession(
          new BootstrapIssueAgentSessionRequest(
              issue.getId(),
              run.getAgentName(),
              sessionId,
              threadId,
              initialCommandKey(run.getId()),
              initialMessage));
    } catch (RuntimeException e) {
      throw sanitizedFailure(HARNESS_BOOTSTRAP_FAILED, e);
    }
  }

  /**
   * 复用工作 Branch 启动新 Run：先确认 Branch 已收尾，再按 Project 策略对齐 Thread YOLO 并确认生效，最后才投递 Run 输入。
   *
   * <p>该顺序本身是安全属性：Harness Thread 的 {@code yoloEnabled} 是实际工具授权快照，Run 输入一旦入队就可能立刻产生模型与
   * 工具调用，因此先投递再对齐等于让本次 Run 按旧策略执行。命令与 reconcile 处于同一物理事务：游标由对齐后的快照冻结，若期间 Harness 已推进游标，接受会以 {@code
   * STALE_COMMAND_CURSOR} 失败并让整个 reconcile 回滚重试，绝不写入陈旧游标。
   */
  private void deliverRunInitialMessage(
      Project project, IssueAgentSession agentSession, IssueRun run, String initialMessage) {
    ThreadSnapshot snapshot = alignedQuiescentSnapshot(project, agentSession.getThreadId());
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER, List.of(new TextMessageContent(initialMessage)))),
            initialCommandKey(run.getId()));
    acceptRunCommand(agentSession.getId(), snapshot, command);
  }

  /**
   * 取回可用于启动 Run 的静止快照：Thread 必须已收尾旧模型/工具调用且没有未消费命令；YOLO 与 Project 策略不一致时先以版本 CAS
   * 对齐，再重新读取快照，用对齐后的游标启动本次 Run。
   *
   * <p>任一环不满足都抛脱敏失败让 reconcile 整体回滚重试：绝不把 Run 输入排到未收尾的 Branch 上，也绝不按与项目策略不符的 YOLO 启动 Run。
   */
  private ThreadSnapshot alignedQuiescentSnapshot(Project project, UUID threadId) {
    ThreadSnapshot snapshot = quiescentSnapshot(threadId);
    if (snapshot.thread().yoloEnabled() == project.isYoloEnabled()) {
      return snapshot;
    }
    ThreadState aligned;
    try {
      aligned =
          requireRuntime()
              .setThreadYolo(
                  new SetThreadYoloCommand(
                      threadId, snapshot.thread().version(), project.isYoloEnabled()));
    } catch (RuntimeException e) {
      throw sanitizedFailure(YOLO_ALIGNMENT_FAILED, e);
    }
    if (aligned == null || aligned.yoloEnabled() != project.isYoloEnabled()) {
      throw sanitizedFailure(
          YOLO_ALIGNMENT_FAILED, new IllegalStateException("Thread YOLO did not take effect"));
    }
    return quiescentSnapshot(threadId);
  }

  /** 读取静止快照：Thread 存在、没有未收尾调用且没有未消费命令，否则按脱敏失败上报交由 reconcile 回滚重试。 */
  private ThreadSnapshot quiescentSnapshot(UUID threadId) {
    ThreadSnapshot snapshot;
    try {
      snapshot = requireRuntime().getThreadSnapshot(threadId);
    } catch (RuntimeException e) {
      throw sanitizedFailure(HARNESS_BOOTSTRAP_FAILED, e);
    }
    if (snapshot == null) {
      throw sanitizedFailure(
          HARNESS_BOOTSTRAP_FAILED,
          new HarnessRuntimeNotFoundException("Thread snapshot is missing"));
    }
    if (isProcessing(snapshot)) {
      throw sanitizedFailure(
          HARNESS_BRANCH_UNSETTLED,
          new IllegalStateException("Thread has unsettled invocations or unconsumed commands"));
    }
    return snapshot;
  }

  void bootstrapIfAvailable(Project project, Issue issue, IssueRun run) {
    if (harnessRuntimes.getIfAvailable() != null) {
      bootstrap(project, issue, run);
    }
  }

  Inspection inspect(Issue issue, IssueRun run) {
    IssueAgentSession relation =
        issueAgentSessionRepository.findByIssueIdAndAgentName(issue.getId(), run.getAgentName());
    if (relation == null) {
      return new Inspection(InspectionStatus.MISSING_SESSION, null, null);
    }
    HarnessRuntime runtime = harnessRuntimes.getIfAvailable();
    if (runtime == null) {
      return new Inspection(InspectionStatus.MISSING_THREAD, relation, null);
    }
    ThreadSnapshot snapshot;
    try {
      snapshot = runtime.getThreadSnapshot(relation.getThreadId());
    } catch (HarnessRuntimeNotFoundException e) {
      return new Inspection(InspectionStatus.MISSING_THREAD, relation, null);
    } catch (RuntimeException e) {
      log.warn("Failed to get thread snapshot for threadId={}", relation.getThreadId(), e);
      return new Inspection(InspectionStatus.MISSING_THREAD, relation, null);
    }
    if (snapshot == null) {
      return new Inspection(InspectionStatus.MISSING_THREAD, relation, null);
    }
    if (isUnknown(snapshot)) {
      return new Inspection(InspectionStatus.UNKNOWN, relation, snapshot);
    }
    if (isProcessing(snapshot)) {
      return new Inspection(InspectionStatus.PROCESSING, relation, snapshot);
    }
    return new Inspection(InspectionStatus.QUIESCENT, relation, snapshot);
  }

  /**
   * 以版本 CAS 对齐 Thread YOLO；失败抛脱敏失败让调用方整体回滚重试，绝不静默吞掉后继续推进。
   *
   * <p>静默吞掉会让 Run 在 Project 策略尚未生效时继续（或让 reconcile 以“已对齐”的假象反复空转），因此对齐失败必须是一个 可重试的明确失败，而不是被忽略的返回值。
   */
  void alignThreadYolo(UUID threadId, long expectedVersion, boolean yoloEnabled) {
    try {
      requireRuntime()
          .setThreadYolo(new SetThreadYoloCommand(threadId, expectedVersion, yoloEnabled));
    } catch (RuntimeException e) {
      throw sanitizedFailure(YOLO_ALIGNMENT_FAILED, e);
    }
  }

  QualifiedSubmission findQualifiedSubmission(
      IssueRun run, ThreadSnapshot snapshot, boolean hasPendingTargetedActivities) {
    if (run.getRole() != IssueRunRole.EXECUTOR
        || run.getStatus() != IssueRunStatus.RUNNING
        || hasPendingTargetedActivities
        || snapshot == null
        || !snapshot.queuedCommands().isEmpty()
        || snapshot.model() != null
        || !allToolsFinished(snapshot.toolSiblings())
        || snapshot.entryPath().openTurnStart().isPresent()) {
      return null;
    }

    List<Entry> entries = snapshot.entryPath().entries();
    Entry turnEndEntry = null;
    for (int i = entries.size() - 1; i >= 0; i--) {
      if (entries.get(i).payload() instanceof TurnEndPayload) {
        turnEndEntry = entries.get(i);
        break;
      }
    }
    if (turnEndEntry == null) {
      return null;
    }

    TurnEndPayload turnEnd = (TurnEndPayload) turnEndEntry.payload();
    if (turnEnd.outcome() != TurnEndOutcome.COMPLETED
        || turnEnd.continueModel()
        || turnEnd.closeRequestId() != null) {
      return null;
    }

    Entry turnStartEntry = null;
    int turnStartIndex = -1;
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(turnEnd.turnStartEntryId())) {
        turnStartEntry = entries.get(i);
        turnStartIndex = i;
        break;
      }
    }
    if (turnStartEntry == null
        || !(turnStartEntry.payload() instanceof TurnStartPayload turnStart)) {
      return null;
    }
    if (turnStart.reason() == TurnStartReason.COMPACTION) {
      return null;
    }
    if (!snapshot.thread().id().equals(turnStart.ownerThreadId())) {
      return null;
    }
    if (run.getCreatedAt() != null && turnStartEntry.createdAt().isBefore(run.getCreatedAt())) {
      return null;
    }

    String lastAssistantText = null;
    boolean hasNonEmptyAssistantText = false;
    boolean canonicalComplete = false;
    for (int i = turnStartIndex + 1;
        i < entries.size() && !entries.get(i).id().equals(turnEndEntry.id());
        i++) {
      Entry e = entries.get(i);
      if (e.payload() instanceof MessagePayload msg
          && msg.message().role() == AgentMessageRole.ASSISTANT) {
        boolean hasToolCalls =
            msg.message().contents().stream().anyMatch(ToolCallMessageContent.class::isInstance);
        if (hasToolCalls) {
          canonicalComplete = false;
          continue;
        }
        if (msg.assistantMetadata() != null
            && msg.assistantMetadata().stopReason() == GenerationStopReason.COMPLETE) {
          canonicalComplete = true;
        } else {
          canonicalComplete = false;
        }
        StringBuilder sb = new StringBuilder();
        for (AgentMessageContent c : msg.message().contents()) {
          if (c instanceof TextMessageContent text && !text.text().trim().isEmpty()) {
            if (!sb.isEmpty()) {
              sb.append("\n");
            }
            sb.append(text.text().trim());
          }
        }
        if (!sb.isEmpty()) {
          hasNonEmptyAssistantText = true;
          lastAssistantText = sb.toString();
        }
      }
    }

    if (canonicalComplete && hasNonEmptyAssistantText && lastAssistantText != null) {
      return new QualifiedSubmission(turnEndEntry.id(), lastAssistantText);
    }
    return null;
  }

  void deliverActivity(
      Issue issue,
      IssueRun run,
      IssueActivity activity,
      IssueAgentSession agentSession,
      ThreadSnapshot snapshot) {
    String header =
        "Activity #"
            + activity.getSequence()
            + " ("
            + activity.getKind()
            + " by "
            + (activity.getActorAgentName() != null
                ? activity.getActorAgentName()
                : activity.getActorType())
            + "):\n";
    String text = header + (activity.getBody() != null ? activity.getBody() : "");
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
            activityDeliveryKey(run.getId(), activity.getSequence()));
    acceptRunCommand(agentSession.getId(), snapshot, command);
  }

  void sendSystemContinuation(
      Issue issue, IssueRun run, IssueAgentSession agentSession, ThreadSnapshot snapshot) {
    NewThreadCommand command =
        new NewThreadCommand(
            new CustomMessageCommandPayload(
                SystemReminder.message("Continue working on issue #" + issue.getNumber() + ".")),
            continuationKey(
                run.getId(),
                run.getContinuationCount(),
                run.getObservedActivitySequence(),
                "SYSTEM"));
    acceptRunCommand(agentSession.getId(), snapshot, command);
  }

  void stopAfterCommit(UUID issueId, String agentName) {
    if (issueId == null || agentName == null) {
      return;
    }
    IssueAgentSession relation =
        issueAgentSessionRepository.findByIssueIdAndAgentName(issueId, agentName);
    if (relation == null) {
      return;
    }
    UUID sessionId = relation.getSessionId();
    Runnable stop = () -> bestEffortStop(sessionId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              stop.run();
            }
          });
    } else {
      stop.run();
    }
  }

  private void acceptRunCommand(
      UUID issueAgentSessionId, ThreadSnapshot snapshot, NewThreadCommand command) {
    try {
      acceptanceOrchestrator.accept(
          new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, issueAgentSessionId),
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  snapshot.thread().id(),
                  snapshot.thread().headEntryId(),
                  snapshot.thread().nextCommandSequence()),
              List.of(command)));
    } catch (RuntimeException e) {
      throw sanitizedFailure("Harness command delivery failed", e);
    }
  }

  private boolean allToolsFinished(List<ToolInvocation> tools) {
    if (tools == null || tools.isEmpty()) {
      return true;
    }
    for (ToolInvocation tool : tools) {
      if (tool.status() == ToolInvocationStatus.RUNNING
          || tool.status() == ToolInvocationStatus.WAITING_APPROVAL
          || tool.status() == ToolInvocationStatus.UNKNOWN) {
        return false;
      }
    }
    return true;
  }

  private boolean isUnknown(ThreadSnapshot snapshot) {
    if (snapshot.model() != null && snapshot.model().status() == ModelInvocationStatus.UNKNOWN) {
      return true;
    }
    for (ToolInvocation sibling : snapshot.toolSiblings()) {
      if (sibling.status() == ToolInvocationStatus.UNKNOWN) {
        return true;
      }
    }
    for (Entry entry : snapshot.entryPath().entries()) {
      if (entry.payload() instanceof MessagePayload message) {
        ToolResultMetadata metadata = message.toolResultMetadata();
        if (metadata != null && metadata.status() == ToolResultStatus.UNKNOWN) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isProcessing(ThreadSnapshot snapshot) {
    if (!snapshot.queuedCommands().isEmpty()) {
      return true;
    }
    ThreadContext context =
        CONTEXT_CLASSIFIER.classify(
            snapshot.thread(), snapshot.entryPath(), snapshot.model(), snapshot.toolSiblings());
    return ThreadRuntimeStatus.from(context).isProcessing();
  }

  private void bestEffortStop(UUID sessionId) {
    HarnessRuntime runtime;
    List<ThreadState> threads;
    try {
      runtime = requireRuntime();
      threads =
          runtime.listThreadsBySession(sessionId).stream()
              .sorted(Comparator.comparing(ThreadState::createdAt).thenComparing(ThreadState::id))
              .toList();
    } catch (RuntimeException e) {
      log.warn("Issue Harness stop failed ({})", e.getClass().getSimpleName());
      return;
    }
    for (ThreadState thread : threads) {
      try {
        runtime.stop(new StopCommand(thread.id(), UUID.randomUUID(), thread.version()));
      } catch (RuntimeException e) {
        log.warn("Issue Harness stop failed ({})", e.getClass().getSimpleName());
      }
    }
  }

  private IllegalStateException sanitizedFailure(String message, RuntimeException failure) {
    // 对外只暴露脱敏后的失败信息；根因按运维需要记入服务端日志。
    log.warn("{} ({})", message, failure.getClass().getSimpleName(), failure);
    return new IllegalStateException(message);
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = harnessRuntimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("Harness runtime is not available");
    }
    return runtime;
  }

  static UUID initialCommandKey(UUID runId) {
    return nameUuid("issue_run_initial:" + runId);
  }

  static UUID activityDeliveryKey(UUID runId, long sequence) {
    return nameUuid("issue_run_activity:" + runId + ":" + sequence);
  }

  static UUID continuationKey(
      UUID runId, int continuationCount, long observedActivitySequence, String actionKind) {
    return nameUuid(
        "issue_run_continuation:"
            + runId
            + ":"
            + continuationCount
            + ":"
            + observedActivitySequence
            + ":"
            + actionKind);
  }

  private static UUID nameUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  enum InspectionStatus {
    MISSING_SESSION,
    MISSING_THREAD,
    UNKNOWN,
    PROCESSING,
    QUIESCENT
  }

  record Inspection(
      InspectionStatus status, IssueAgentSession agentSession, ThreadSnapshot snapshot) {

    Inspection {
      Objects.requireNonNull(status, "status");
      if ((status == InspectionStatus.PROCESSING || status == InspectionStatus.QUIESCENT)
          && snapshot == null) {
        throw new IllegalArgumentException("snapshot is required for live Harness states");
      }
    }

    ThreadSnapshot requireQuiescentSnapshot() {
      if (status != InspectionStatus.QUIESCENT) {
        throw new IllegalStateException("Harness is not quiescent");
      }
      return snapshot;
    }
  }

  record QualifiedSubmission(UUID finalEntryId, String summary) {
    QualifiedSubmission {
      Objects.requireNonNull(finalEntryId, "finalEntryId");
      Objects.requireNonNull(summary, "summary");
    }
  }
}
