package fun.fengwk.kkstudio.platform.project.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
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
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueRunSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue Controller 与 Harness 的边界。
 *
 * <p>本类只负责投影 Harness 状态、提交幂等命令和延迟停止 Session；Issue/Run 状态迁移由 {@link IssueReconciler} 负责。
 */
@Slf4j
@Service
class IssueHarnessController {

  private static final ThreadContextClassifier CONTEXT_CLASSIFIER = new ThreadContextClassifier();

  private final ProjectSessionRepository projectSessionRepository;
  private final IssueRunSessionRepository issueRunSessionRepository;
  private final ProjectHarnessSessionBootstrapService bootstrapService;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private final ObjectProvider<HarnessRuntime> harnessRuntimes;

  IssueHarnessController(
      ProjectSessionRepository projectSessionRepository,
      IssueRunSessionRepository issueRunSessionRepository,
      ProjectHarnessSessionBootstrapService bootstrapService,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
      ObjectProvider<HarnessRuntime> harnessRuntimes) {
    this.projectSessionRepository =
        Objects.requireNonNull(projectSessionRepository, "projectSessionRepository");
    this.issueRunSessionRepository =
        Objects.requireNonNull(issueRunSessionRepository, "issueRunSessionRepository");
    this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
    this.harnessRuntimes = Objects.requireNonNull(harnessRuntimes, "harnessRuntimes");
  }

  void bootstrap(Issue issue, IssueRun run) {
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    String action = run.getRole() == IssueRunRole.EXECUTOR ? "Execute" : "Review";
    String initialMessage = action + " issue #" + issue.getNumber() + ": " + issue.getTitle();
    try {
      bootstrapService.bootstrapIssueRunSession(
          new BootstrapIssueRunSessionRequest(
              run.getId(), sessionId, threadId, initialCommandKey(run.getId()), initialMessage));
    } catch (RuntimeException e) {
      throw sanitizedFailure("Harness session bootstrap failed", e);
    }
  }

  void bootstrapIfAvailable(Issue issue, IssueRun run) {
    if (harnessRuntimes.getIfAvailable() != null) {
      bootstrap(issue, run);
    }
  }

  Inspection inspect(IssueRun run) {
    IssueRunSession relation = issueRunSessionRepository.findByRunId(run.getId());
    if (relation == null) {
      return new Inspection(InspectionStatus.MISSING_SESSION, null);
    }
    ThreadState thread = earliestThread(relation.getSessionId());
    if (thread == null) {
      return new Inspection(InspectionStatus.MISSING_THREAD, null);
    }
    ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(thread.id());
    if (isUnknown(snapshot)) {
      return new Inspection(InspectionStatus.UNKNOWN, snapshot);
    }
    if (isProcessing(snapshot)) {
      return new Inspection(InspectionStatus.PROCESSING, snapshot);
    }
    return new Inspection(InspectionStatus.QUIESCENT, snapshot);
  }

  void sendUserContinuation(Issue issue, IssueRun run, IssueInput input, Inspection inspection) {
    String message =
        "New issue input (spec revision "
            + issue.getSpecRevision()
            + ", sequence "
            + input.getSequence()
            + ", kind "
            + input.getKind()
            + "):\n"
            + input.getBody();
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(AgentMessage.user(message)),
            continuationKey(
                run.getId(),
                run.getContinuationCount(),
                issue.getSpecRevision(),
                input.getSequence(),
                "USER"));
    acceptRunCommand(run.getId(), inspection, command);
  }

  void sendSystemContinuation(Issue issue, IssueRun run, Inspection inspection) {
    NewThreadCommand command =
        new NewThreadCommand(
            new CustomMessageCommandPayload(
                SystemReminder.message("Continue working on issue #" + issue.getNumber() + ".")),
            continuationKey(
                run.getId(),
                run.getContinuationCount(),
                run.getObservedSpecRevision(),
                run.getObservedInputSequence(),
                "SYSTEM"));
    acceptRunCommand(run.getId(), inspection, command);
  }

  void deliverAttention(Project project, Issue issue, IssueRun run) {
    ProjectSession relation = projectSessionRepository.findByProjectId(project.getId());
    if (relation == null) {
      return;
    }
    ThreadState thread = earliestThread(relation.getSessionId());
    if (thread == null) {
      return;
    }
    UUID idempotencyKey = coordinatorAttentionKey(run);
    try {
      if (requireRuntime().findThreadCommand(thread.id(), idempotencyKey).isPresent()) {
        return;
      }
      NewThreadCommand command =
          new NewThreadCommand(
              new CustomMessageCommandPayload(
                  SystemReminder.message(
                      "Issue #"
                          + issue.getNumber()
                          + " run entered "
                          + run.getStatus()
                          + (run.getWaitingReason() == null
                              ? "."
                              : ": " + run.getWaitingReason()))),
              idempotencyKey);
      acceptanceOrchestrator.accept(
          new OwnerRef(OwnerType.PROJECT, project.getId()),
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  thread.id(), thread.headEntryId(), thread.nextCommandSequence()),
              List.of(command)));
    } catch (RuntimeException e) {
      throw sanitizedFailure("Coordinator attention delivery failed", e);
    }
  }

  void stopAfterCommit(IssueRun run) {
    IssueRunSession relation = issueRunSessionRepository.findByRunId(run.getId());
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

  private void acceptRunCommand(UUID runId, Inspection inspection, NewThreadCommand command) {
    ThreadSnapshot snapshot = inspection.requireQuiescentSnapshot();
    try {
      acceptanceOrchestrator.accept(
          new OwnerRef(OwnerType.ISSUE_RUN, runId),
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  snapshot.thread().id(),
                  snapshot.thread().headEntryId(),
                  snapshot.thread().nextCommandSequence()),
              List.of(command)));
    } catch (RuntimeException e) {
      throw sanitizedFailure("Harness continuation delivery failed", e);
    }
  }

  private ThreadState earliestThread(UUID sessionId) {
    return requireRuntime().listThreadsBySession(sessionId).stream()
        .min(Comparator.comparing(ThreadState::createdAt).thenComparing(ThreadState::id))
        .orElse(null);
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
    log.warn("{} ({})", message, failure.getClass().getSimpleName());
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

  static UUID continuationKey(
      UUID runId,
      int continuationCount,
      long observedSpecRevision,
      long observedInputSequence,
      String actionKind) {
    return nameUuid(
        "issue_run_continuation:"
            + runId
            + ":"
            + continuationCount
            + ":"
            + observedSpecRevision
            + ":"
            + observedInputSequence
            + ":"
            + actionKind);
  }

  static UUID coordinatorAttentionKey(IssueRun run) {
    return nameUuid(
        "coordinator_attention:" + run.getId() + ":" + run.getStatus() + ":" + run.getVersion());
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

  record Inspection(InspectionStatus status, ThreadSnapshot snapshot) {

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
}
