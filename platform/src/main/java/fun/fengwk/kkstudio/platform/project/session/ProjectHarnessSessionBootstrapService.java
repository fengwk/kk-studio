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
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.impl.ProjectValidationUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 为 IssueAgentSession 原子引导创建 Harness Session、ROOT Entry、初始 Thread 与 Command， 并在同一物理事务内建立归属关系。 */
@Service
public class ProjectHarnessSessionBootstrapService {

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private final IssueEvidenceService issueEvidenceService;

  public ProjectHarnessSessionBootstrapService(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      AgentBranchSettingsMaterializer settingsMaterializer,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
      IssueEvidenceService issueEvidenceService) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
  }

  @Transactional
  public AcceptedCommands bootstrapIssueAgentSession(BootstrapIssueAgentSessionRequest request) {
    Objects.requireNonNull(request, "request");
    String message = validateMessage(request.initialMessage());

    Issue issueRef = issueRepository.getById(request.issueId());
    if (issueRef == null) {
      throw new AiResourceNotFoundException("issue");
    }

    UUID projectId = issueRef.getProjectId();
    Project project = projectRepository.lockForShare(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    Issue issue = issueRepository.lockById(request.issueId());
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    if (!issue.getProjectId().equals(project.getId())) {
      throw new AiValidationException("owner_state", "Inconsistent owner hierarchy state");
    }

    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot bootstrap session for archived project");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Cannot bootstrap session for archived issue");
    }

    String agentName = request.agentName().trim();
    boolean matchesAssignee = agentName.equals(issue.getAssigneeAgentName());
    boolean matchesReviewer = agentName.equals(issue.getReviewerAgentName());
    if (!matchesAssignee && !matchesReviewer) {
      throw new AiValidationException(
          "issue_agent_session",
          "Agent " + agentName + " is not assigned to issue " + issue.getId());
    }

    IssueAgentSession existing =
        issueAgentSessionRepository.findByIssueIdAndAgentName(issue.getId(), agentName);
    if (existing != null && !existing.getSessionId().equals(request.sessionId())) {
      throw new AiValidationException(
          "session_owner", "Issue agent is already bound to a different session");
    }

    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(existing != null ? existing.getId() : UUID.randomUUID())
            .issueId(issue.getId())
            .agentName(agentName)
            .sessionId(request.sessionId())
            .threadId(request.threadId())
            .build();
    agentSession = issueAgentSessionRepository.bindOrGet(agentSession);

    BranchSettings settings = settingsMaterializer.materialize(agentName);
    NewThreadCommand command = buildInitialCommand(message, request.initialCommandIdempotencyKey());
    AcceptCommandsTarget.NewSession target =
        new AcceptCommandsTarget.NewSession(
            request.sessionId(), request.threadId(), settings, null, project.isYoloEnabled());
    AcceptedCommands accepted =
        acceptanceOrchestrator.accept(
            new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, agentSession.getId()),
            new AcceptCommandsCommand(target, List.of(command)));
    // Session 行已建立：把该 Issue 已发布证据的指定 Blob 引用幂等授予它，使后续参与者能读取公开证据（只授已发布
    // Blob，不存在通配可见性）。授权失败让整个引导回滚，绝不留下已接受命令却看不到公开证据的 Session。
    issueEvidenceService.grantPublishedEvidence(issue.getId(), request.sessionId());
    return accepted;
  }

  @Transactional
  public AcceptedCommands bootstrapIssueAgentSession(
      UUID issueId,
      String agentName,
      UUID sessionId,
      UUID threadId,
      UUID initialCommandIdempotencyKey,
      String initialMessage) {
    return bootstrapIssueAgentSession(
        new BootstrapIssueAgentSessionRequest(
            issueId, agentName, sessionId, threadId, initialCommandIdempotencyKey, initialMessage));
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
