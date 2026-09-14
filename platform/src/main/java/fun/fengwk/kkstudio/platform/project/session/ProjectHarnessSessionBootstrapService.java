package fun.fengwk.kkstudio.platform.project.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.impl.ProjectValidationUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 为 Project Coordinator 与 IssueRun 原子引导创建 Harness Session、ROOT Entry、初始 Thread 与 Command，
 * 并在同一物理事务内建立归属关系边。
 */
@Service
public class ProjectHarnessSessionBootstrapService {

  private final ProjectRepository projectRepository;
  private final ProjectSessionRepository projectSessionRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueRunSessionRepository issueRunSessionRepository;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;

  public ProjectHarnessSessionBootstrapService(
      ProjectRepository projectRepository,
      ProjectSessionRepository projectSessionRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueRunSessionRepository issueRunSessionRepository,
      AgentBranchSettingsMaterializer settingsMaterializer,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.projectSessionRepository =
        Objects.requireNonNull(projectSessionRepository, "projectSessionRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueRunSessionRepository =
        Objects.requireNonNull(issueRunSessionRepository, "issueRunSessionRepository");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
  }

  @Transactional
  public AcceptedCommands bootstrapProjectSession(BootstrapProjectSessionRequest request) {
    Objects.requireNonNull(request, "request");
    String message = validateMessage(request.initialMessage());

    Project project = projectRepository.lockForShare(request.projectId());
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot bootstrap session for archived project");
    }
    if (project.getCoordinatorAgentName() == null || project.getCoordinatorAgentName().isBlank()) {
      throw new AiValidationException("project", "Project coordinator agent is missing");
    }

    ProjectSession existing = projectSessionRepository.findByProjectId(request.projectId());
    if (existing != null && !existing.getSessionId().equals(request.sessionId())) {
      throw new AiValidationException(
          "session_owner", "Project is already bound to a different session");
    }

    BranchSettings settings = settingsMaterializer.materialize(project.getCoordinatorAgentName());
    NewThreadCommand command = buildInitialCommand(message, request.initialCommandIdempotencyKey());
    AcceptCommandsTarget.NewSession target =
        new AcceptCommandsTarget.NewSession(
            request.sessionId(), request.threadId(), settings, null, false);
    return acceptanceOrchestrator.accept(
        new OwnerRef(OwnerType.PROJECT, request.projectId()),
        new AcceptCommandsCommand(target, List.of(command)));
  }

  @Transactional
  public AcceptedCommands bootstrapProjectSession(
      UUID projectId,
      UUID sessionId,
      UUID threadId,
      UUID initialCommandIdempotencyKey,
      String initialMessage) {
    return bootstrapProjectSession(
        new BootstrapProjectSessionRequest(
            projectId, sessionId, threadId, initialCommandIdempotencyKey, initialMessage));
  }

  @Transactional
  public AcceptedCommands bootstrapIssueRunSession(BootstrapIssueRunSessionRequest request) {
    Objects.requireNonNull(request, "request");
    String message = validateMessage(request.initialMessage());

    // 锁序必须严格保持 Project -> Issue -> IssueRun
    IssueRun runRef = issueRunRepository.getById(request.runId());
    if (runRef == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    Issue issueRef = issueRepository.getById(runRef.getIssueId());
    if (issueRef == null) {
      throw new AiResourceNotFoundException("issue");
    }

    UUID projectId = issueRef.getProjectId();
    UUID issueId = issueRef.getId();

    Project project = projectRepository.lockForShare(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    IssueRun run = issueRunRepository.lockById(request.runId());
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }

    // 锁下验证层级一致性（避免在加锁前被重定向或读锁竞态）
    if (!issue.getProjectId().equals(project.getId()) || !run.getIssueId().equals(issue.getId())) {
      throw new AiValidationException("owner_state", "Inconsistent owner hierarchy state");
    }

    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot bootstrap session for archived project");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Cannot bootstrap session for archived issue");
    }
    if (run.getActorType() != IssueRunActorType.AGENT) {
      throw new AiValidationException("issue_run", "Cannot bootstrap session for non-agent run");
    }
    if (run.getAgentName() == null || run.getAgentName().isBlank()) {
      throw new AiValidationException("issue_run", "Issue run agent is missing");
    }
    if (run.getStatus() == null || !run.getStatus().isActive()) {
      throw new AiValidationException("issue_run", "Cannot bootstrap session for non-active run");
    }

    IssueRunSession existing = issueRunSessionRepository.findByRunId(request.runId());
    if (existing != null && !existing.getSessionId().equals(request.sessionId())) {
      throw new AiValidationException(
          "session_owner", "Issue run is already bound to a different session");
    }

    BranchSettings settings = settingsMaterializer.materialize(run.getAgentName());
    NewThreadCommand command = buildInitialCommand(message, request.initialCommandIdempotencyKey());
    AcceptCommandsTarget.NewSession target =
        new AcceptCommandsTarget.NewSession(
            request.sessionId(), request.threadId(), settings, null, false);
    return acceptanceOrchestrator.accept(
        new OwnerRef(OwnerType.ISSUE_RUN, request.runId()),
        new AcceptCommandsCommand(target, List.of(command)));
  }

  @Transactional
  public AcceptedCommands bootstrapIssueRunSession(
      UUID runId,
      UUID sessionId,
      UUID threadId,
      UUID initialCommandIdempotencyKey,
      String initialMessage) {
    return bootstrapIssueRunSession(
        new BootstrapIssueRunSessionRequest(
            runId, sessionId, threadId, initialCommandIdempotencyKey, initialMessage));
  }

  private String validateMessage(String initialMessage) {
    ProjectValidationUtils.validateUtf8Bytes(initialMessage, "initialMessage", 1048576, true);
    return initialMessage;
  }

  private NewThreadCommand buildInitialCommand(String message, UUID idempotencyKey) {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(message))));
    String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
    return new NewThreadCommand(payload, idempotencyKey, requestHash);
  }
}
