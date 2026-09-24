package fun.fengwk.kkstudio.platform.project.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatusTransition;
import fun.fengwk.kkstudio.platform.project.model.IssueTransitionAction;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueRunServiceImpl implements IssueRunService {

  /** UNKNOWN 重试的人工核对说明上限：65536 个码点且不超过 65536 UTF-8 字节。 */
  private static final int MAX_VERIFICATION_CHARS = 65536;

  private static final int MAX_VERIFICATION_UTF8_BYTES = 65536;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueWorkStore workStore;
  private final IssueEvidenceService issueEvidenceService;
  private final ObjectMapper objectMapper;

  @Transactional
  @Override
  public IssueRun startExecutorRun(
      UUID issueId, String agentName, Instant deadline, int maxContinuations) {
    Objects.requireNonNull(issueId, "issueId");
    if (maxContinuations < 0) {
      throw new AiValidationException("issue_run", "maxContinuations must not be negative");
    }
    String trimmedAgent = ProjectValidationUtils.trimAndValidate(agentName, "agentName", 128, true);

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = initialIssue.getProjectId();

    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot start run in an archived project");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(projectId)) {
      throw new AiValidationException("issue", "Issue project mismatch");
    }
    if (issue.getStatus() != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start run: issue must be in TODO status, current is " + issue.getStatus());
    }
    if (issue.getAssigneeAgentName() == null
        || !issue.getAssigneeAgentName().equals(trimmedAgent)) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: agentName mismatch with issue assignee");
    }

    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issueId);
    List<UUID> depIssueIds =
        deps.stream().map(IssueDependency::getDependsOnIssueId).sorted().toList();
    for (UUID depId : depIssueIds) {
      Issue depIssue = issueRepository.lockById(depId);
      if (depIssue == null || depIssue.getStatus() != IssueStatus.DONE) {
        throw new AiValidationException("issue_run", "Cannot start run: dependency is not DONE");
      }
    }

    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: an active run already exists for issue");
    }

    long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
    UUID runId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.EXECUTOR)
            .agentName(trimmedAgent)
            .submissionRunId(null)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedActivitySequence(0L)
            .continuationCount(0)
            .maxContinuations(maxContinuations)
            .deadline(deadline)
            .waitingReason(null)
            .result(null)
            .terminalActionId(null)
            .version(0L)
            .build();

    boolean runCreated = issueRunRepository.create(run);
    if (!runCreated) {
      throw new AiValidationException("issue_run", "Failed to create executor run");
    }

    issue.setStatus(
        IssueStatusTransition.transition(issue.getStatus(), IssueTransitionAction.START_EXECUTION));
    boolean issueUpdated = issueRepository.updateById(issue, issue.getVersion());
    if (!issueUpdated) {
      throw new AiValidationException("issue", "Failed to update issue status to IN_PROGRESS");
    }

    workStore.requestWork(issueId, Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun startReviewerRun(
      UUID issueId, String agentName, Instant deadline, int maxContinuations) {
    Objects.requireNonNull(issueId, "issueId");
    if (maxContinuations < 0) {
      throw new AiValidationException("issue_run", "maxContinuations must not be negative");
    }
    String trimmedAgent = ProjectValidationUtils.trimAndValidate(agentName, "agentName", 128, true);

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = initialIssue.getProjectId();

    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot start run in an archived project");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(projectId)) {
      throw new AiValidationException("issue", "Issue project mismatch");
    }
    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start reviewer run: issue must be in IN_REVIEW status, current is "
              + issue.getStatus());
    }
    if (issue.getReviewerAgentName() == null
        || !issue.getReviewerAgentName().equals(trimmedAgent)) {
      throw new AiValidationException(
          "issue_run", "Cannot start reviewer run: agentName mismatch with issue reviewer");
    }
    if (trimmedAgent.equals(issue.getAssigneeAgentName())) {
      throw new AiValidationException(
          "issue_run", "Reviewer agent must differ from executor assignee");
    }

    List<IssueRun> runs = issueRunRepository.listByIssueId(issueId);
    UUID submissionRunId = null;
    for (int i = runs.size() - 1; i >= 0; i--) {
      IssueRun r = runs.get(i);
      if (r.getRole() == IssueRunRole.EXECUTOR && r.getOutcome() == IssueRunOutcome.SUBMITTED) {
        submissionRunId = r.getId();
        break;
      }
    }
    if (submissionRunId == null) {
      throw new AiValidationException("issue_run", "No submitted executor run found to review");
    }

    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: an active run already exists for issue");
    }

    long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
    UUID runId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.REVIEWER)
            .agentName(trimmedAgent)
            .submissionRunId(submissionRunId)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedActivitySequence(0L)
            .continuationCount(0)
            .maxContinuations(maxContinuations)
            .deadline(deadline)
            .waitingReason(null)
            .result(null)
            .terminalActionId(null)
            .version(0L)
            .build();

    boolean runCreated = issueRunRepository.create(run);
    if (!runCreated) {
      throw new AiValidationException("issue_run", "Failed to create reviewer run");
    }

    workStore.requestWork(issueId, Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun completeExecutorRun(
      UUID runId, String terminalActionId, String summary, String verification) {
    Objects.requireNonNull(runId, "runId");
    String trimmedActionId =
        ProjectValidationUtils.trimAndValidate(terminalActionId, "terminalActionId", 255, true);

    String resultJson = toJson(summary, verification);

    IssueRun existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactSubmitReplay(existingByAction, runId, resultJson);
    }

    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactSubmitReplay(existingByAction, runId, resultJson);
    }

    if (run.isTerminal()) {
      throw new AiValidationException(
          "issue_run", "Run is already terminal and cannot be submitted");
    }

    if (run.getRole() != IssueRunRole.EXECUTOR || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Only RUNNING EXECUTOR run can be completed, current role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_PROGRESS for submit, current status=" + issue.getStatus());
    }

    run.setStatus(IssueRunStatus.COMPLETED);
    run.setOutcome(IssueRunOutcome.SUBMITTED);
    run.setTerminalActionId(trimmedActionId);
    run.setResult(resultJson);
    run.setWaitingReason(null);
    run.setCompletedAt(Instant.now());

    try {
      boolean runUpdated = issueRunRepository.updateById(run, run.getVersion());
      if (!runUpdated) {
        throw new AiValidationException("issue_run", "Failed to update run to COMPLETED");
      }
    } catch (DataIntegrityViolationException e) {
      if (isTerminalActionUniqueConflict(e)) {
        throw new AiValidationException("issue_run", "Terminal action ID conflict");
      }
      throw e;
    }

    issue.setStatus(
        IssueStatusTransition.transition(issue.getStatus(), IssueTransitionAction.SUBMIT));
    boolean issueUpdated = issueRepository.updateById(issue, issue.getVersion());
    if (!issueUpdated) {
      throw new AiValidationException("issue", "Failed to update issue status to IN_REVIEW");
    }

    // 与提交同一事务：只公开来源 Session 在提交时确实持有、且 final 正文明确引用的产物；不满足即拒绝整个提交，
    // 绝不静默公开私有资源。
    issueEvidenceService.publishExecutorEvidence(issueId, run.getId(), run.getAgentName(), summary);

    workStore.requestWork(issue.getId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun requestInput(UUID runId, String question, String context) {
    Objects.requireNonNull(runId, "runId");
    String trimmedQuestion =
        ProjectValidationUtils.trimAndValidate(question, "question", 16384, true);
    ProjectValidationUtils.validateUtf8Bytes(trimmedQuestion, "question", 16384, true);
    String waitingReason;
    if (context != null && !context.isBlank()) {
      waitingReason = trimmedQuestion + "\n\nContext:\n" + context.trim();
    } else {
      waitingReason = trimmedQuestion;
    }
    ProjectValidationUtils.validateUtf8Bytes(waitingReason, "waiting_reason", 16384, true);

    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    if (run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run", "Only RUNNING run can request input, current status=" + run.getStatus());
    }

    if (!run.getStatus().canTransitionTo(IssueRunStatus.WAITING_HUMAN)) {
      throw new AiValidationException(
          "issue_run", "Cannot transition run from " + run.getStatus() + " to WAITING_HUMAN");
    }

    run.setStatus(IssueRunStatus.WAITING_HUMAN);
    run.setWaitingReason(waitingReason);
    boolean runUpdated = issueRunRepository.updateById(run, run.getVersion());
    if (!runUpdated) {
      throw new AiValidationException("issue_run", "Failed to update run to WAITING_HUMAN");
    }

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.INSTRUCTION)
            .actorType(IssueActivityActorType.AGENT)
            .actorAgentName(run.getAgentName())
            .targetRole(null)
            .runId(run.getId())
            .body(waitingReason)
            .build();
    issueActivityRepository.appendOrGet(activity);

    workStore.requestWork(issue.getId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun reviewByAgent(
      UUID runId,
      String reviewerAgentName,
      String terminalActionId,
      ReviewDecision decision,
      String reason) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(decision, "decision");
    String trimmedActionId =
        ProjectValidationUtils.trimAndValidate(terminalActionId, "terminalActionId", 255, true);
    String trimmedReviewerAgent =
        ProjectValidationUtils.trimAndValidate(reviewerAgentName, "reviewerAgentName", 128, true);

    String resultJson = toJson(reason, null);
    IssueRunOutcome expectedOutcome =
        decision == ReviewDecision.APPROVE
            ? IssueRunOutcome.APPROVED
            : IssueRunOutcome.CHANGES_REQUESTED;

    IssueRun existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactReviewReplay(
          existingByAction, runId, trimmedReviewerAgent, expectedOutcome, resultJson);
    }

    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = initialIssue.getProjectId();
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactReviewReplay(
          existingByAction, runId, trimmedReviewerAgent, expectedOutcome, resultJson);
    }

    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_REVIEW to be reviewed, current is " + issue.getStatus());
    }

    if (run.isTerminal()) {
      throw new AiValidationException(
          "issue_run", "Run is already terminal and cannot be reviewed");
    }

    if (run.getRole() != IssueRunRole.REVIEWER || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Agent review requires a RUNNING REVIEWER run, found role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    String expectedAgent = issue.getReviewerAgentName();
    if (expectedAgent == null
        || !expectedAgent.equals(run.getAgentName())
        || !expectedAgent.equals(trimmedReviewerAgent)) {
      throw new AiValidationException("issue_run", "Reviewer agent identity mismatch");
    }

    run.setStatus(IssueRunStatus.COMPLETED);
    run.setOutcome(expectedOutcome);
    run.setTerminalActionId(trimmedActionId);
    run.setResult(resultJson);
    run.setWaitingReason(null);
    run.setCompletedAt(Instant.now());

    try {
      boolean runUpdated = issueRunRepository.updateById(run, run.getVersion());
      if (!runUpdated) {
        throw new AiValidationException("issue_run", "Failed to update reviewer run");
      }
    } catch (DataIntegrityViolationException e) {
      if (isTerminalActionUniqueConflict(e)) {
        throw new AiValidationException("issue_run", "Terminal action ID conflict");
      }
      throw e;
    }

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.AGENT)
            .actorAgentName(trimmedReviewerAgent)
            .runId(run.getId())
            .submissionRunId(run.getSubmissionRunId())
            .decision(decision)
            .body(reason != null ? reason : "")
            .idempotencyKey("review:" + trimmedActionId)
            .build();
    issueActivityRepository.appendOrGet(activity);

    if (decision == ReviewDecision.APPROVE) {
      issue.setStatus(
          IssueStatusTransition.transition(issue.getStatus(), IssueTransitionAction.APPROVE));
    } else {
      long windowStart = issueActivityRepository.findReviewWindowStartSequence(issueId);
      long rejections = issueActivityRepository.countRejectionsSince(issueId, windowStart);
      if (rejections < project.getMaxReviewRejections()) {
        issue.setStatus(IssueStatus.TODO);
      } else {
        issue.setStatus(IssueStatus.BLOCKED);
      }
    }

    boolean issueUpdated = issueRepository.updateById(issue, issue.getVersion());
    if (!issueUpdated) {
      throw new AiValidationException("issue", "Failed to update issue status");
    }

    workStore.requestWork(issueId, Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public void reviewByHuman(
      UUID issueId, ReviewDecision decision, String reason, String idempotencyKey) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(decision, "decision");
    String trimmedKey =
        ProjectValidationUtils.trimAndValidate(idempotencyKey, "idempotencyKey", 128, false);

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = initialIssue.getProjectId();
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (!trimmedKey.isBlank()) {
      IssueActivity existing =
          issueActivityRepository.findByIssueIdAndIdempotencyKey(issueId, trimmedKey);
      if (existing != null) {
        // 相同审查动作的幂等重放：业务效果已落地，绝不重复决定、重复计数或改变已提交结果；
        // 与已落库决定矛盾的请求、非人工决定或非决定记录都必须明确拒绝。
        if (existing.getKind() != IssueActivityKind.REVIEW_DECISION
            || existing.getActorType() != IssueActivityActorType.HUMAN
            || existing.getDecision() != decision) {
          throw new AiValidationException("issue_activity", "Review decision conflict");
        }
        return;
      }
    }
    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue", "Issue must be in IN_REVIEW to be reviewed, current is " + issue.getStatus());
    }

    List<IssueRun> runs = issueRunRepository.listByIssueId(issueId);
    UUID submissionRunId = null;
    for (int i = runs.size() - 1; i >= 0; i--) {
      IssueRun r = runs.get(i);
      if (r.getRole() == IssueRunRole.EXECUTOR && r.getOutcome() == IssueRunOutcome.SUBMITTED) {
        submissionRunId = r.getId();
        break;
      }
    }
    if (submissionRunId == null) {
      throw new AiValidationException("issue", "No submitted executor run found to review");
    }

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.HUMAN)
            .actorAgentName(null)
            .runId(null)
            .submissionRunId(submissionRunId)
            .decision(decision)
            .body(reason != null ? reason : "")
            .idempotencyKey(trimmedKey)
            .build();
    issueActivityRepository.appendOrGet(activity);

    if (decision == ReviewDecision.APPROVE) {
      issue.setStatus(
          IssueStatusTransition.transition(issue.getStatus(), IssueTransitionAction.APPROVE));
    } else {
      long windowStart = issueActivityRepository.findReviewWindowStartSequence(issueId);
      long rejections = issueActivityRepository.countRejectionsSince(issueId, windowStart);
      if (rejections < project.getMaxReviewRejections()) {
        issue.setStatus(IssueStatus.TODO);
      } else {
        issue.setStatus(IssueStatus.BLOCKED);
      }
    }

    boolean issueUpdated = issueRepository.updateById(issue, issue.getVersion());
    if (!issueUpdated) {
      throw new AiValidationException("issue", "Failed to update issue status");
    }

    workStore.requestWork(issueId, Instant.now());
  }

  @Transactional
  @Override
  public IssueRun failRun(UUID runId, IssueRunStatus terminalStatus, String waitingReason) {
    Objects.requireNonNull(runId, "runId");
    if (terminalStatus != IssueRunStatus.FAILED && terminalStatus != IssueRunStatus.UNKNOWN) {
      throw new AiValidationException(
          "issue_run", "Terminal status must be FAILED or UNKNOWN, but was " + terminalStatus);
    }
    String trimmedReason =
        ProjectValidationUtils.trimAndValidate(waitingReason, "waiting_reason", 16384, true);
    ProjectValidationUtils.validateUtf8Bytes(trimmedReason, "waiting_reason", 16384, true);

    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    if (run.isTerminal()) {
      throw new AiValidationException("issue_run", "Run is already terminal and cannot be failed");
    }
    if (!run.getStatus().canTransitionTo(terminalStatus)) {
      throw new AiValidationException(
          "issue_run", "Cannot transition run from " + run.getStatus() + " to " + terminalStatus);
    }

    run.setStatus(terminalStatus);
    run.setWaitingReason(trimmedReason);
    run.setCompletedAt(Instant.now());

    boolean runUpdated = issueRunRepository.updateById(run, run.getVersion());
    if (!runUpdated) {
      throw new AiValidationException("issue_run", "Failed to fail run");
    }

    workStore.requestWork(run.getIssueId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueActivity retryRun(UUID issueId, String idempotencyKey, String verification) {
    Objects.requireNonNull(issueId, "issueId");
    String trimmedKey =
        ProjectValidationUtils.trimAndValidate(idempotencyKey, "idempotencyKey", 128, true);

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue_run", "Cannot retry archived issue");
    }
    if (issue.isTerminal()) {
      throw new AiValidationException("issue_run", "Cannot retry terminal issue");
    }

    IssueRun latestRun = issueRunRepository.findLatestByIssueId(issueId);
    if (latestRun == null
        || (latestRun.getStatus() != IssueRunStatus.FAILED
            && latestRun.getStatus() != IssueRunStatus.UNKNOWN)) {
      throw new AiValidationException(
          "issue_run",
          "Retry is only allowed when the latest run is FAILED or UNKNOWN, but was "
              + (latestRun != null ? latestRun.getStatus() : "null"));
    }

    // UNKNOWN 意味着已派发的调用是否有外部副作用不可判定，必须由人工先核对残留调用并留下说明。
    String trimmedVerification =
        ProjectValidationUtils.trimAndValidate(
            verification,
            "verification",
            MAX_VERIFICATION_CHARS,
            latestRun.getStatus() == IssueRunStatus.UNKNOWN);
    if (trimmedVerification != null) {
      ProjectValidationUtils.validateUtf8Bytes(
          trimmedVerification, "verification", MAX_VERIFICATION_UTF8_BYTES, true);
    }

    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException("issue_run", "Cannot retry while an active run exists");
    }

    String body = retryActivityBody(latestRun.getId(), trimmedVerification);
    IssueActivity retryActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.RETRY)
            .actorType(IssueActivityActorType.HUMAN)
            .body(body)
            .idempotencyKey(trimmedKey)
            .build();

    IssueActivity appended = issueActivityRepository.appendOrGet(retryActivity);
    // 同一 idempotencyKey 只允许重放同一请求：正文不同说明该键被改写的请求复用，拒绝而不是返回他人的事实。
    if (appended != null && !body.equals(appended.getBody())) {
      throw new AiValidationException(
          "idempotencyKey", "idempotencyKey was already used for a different retry request");
    }
    workStore.requestWork(issueId, Instant.now());
    return appended;
  }

  /** RETRY 正文由重试目标 Run 与人工核对说明确定性构成，因此相同请求重放必然得到相同正文。 */
  private static String retryActivityBody(UUID latestRunId, String verification) {
    if (verification == null) {
      return "Retry requested for run " + latestRunId;
    }
    return "Retry requested for run " + latestRunId + "\nHuman verification: " + verification;
  }

  @Override
  public IssueRun getRun(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run");
    }
    return run;
  }

  @Override
  public IssueRun getActiveRun(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueRunRepository.findActiveByIssueId(issueId);
  }

  @Override
  public IssueRun getLatestRun(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueRunRepository.findLatestByIssueId(issueId);
  }

  @Override
  public List<IssueRun> listRuns(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueRunRepository.listByIssueId(issueId);
  }

  @Override
  public IssueAgentSession getAgentSession(UUID issueId, String agentName) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(agentName, "agentName");
    return issueAgentSessionRepository.findByIssueIdAndAgentName(issueId, agentName);
  }

  @Override
  public IssueAgentSession findAgentSession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return issueAgentSessionRepository.findBySessionId(sessionId);
  }

  @Override
  public IssueAgentSession findAgentSessionByThreadId(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return issueAgentSessionRepository.findByThreadId(threadId);
  }

  private String toJson(String summary, String verification) {
    ProjectValidationUtils.validateUtf8Bytes(summary, "summary", 65536, false);
    ProjectValidationUtils.validateUtf8Bytes(verification, "verification", 65536, false);
    try {
      Map<String, String> map = new LinkedHashMap<>();
      if (summary != null) {
        map.put("summary", summary);
      }
      if (verification != null) {
        map.put("verification", verification);
      }
      String json = objectMapper.writeValueAsString(map);
      int jsonbFormattingBytes = map.isEmpty() ? 0 : 2 * map.size() - 1;
      ProjectValidationUtils.validateUtf8Bytes(
          json + " ".repeat(jsonbFormattingBytes), "result", 65536, false);
      return json;
    } catch (JsonProcessingException e) {
      throw new AiValidationException("issue_run", "Failed to serialize run result to JSON");
    }
  }

  private boolean checkJsonEquivalent(String actualJson, String expectedJson) {
    if (Objects.equals(actualJson, expectedJson)) {
      return true;
    }
    if (actualJson == null || expectedJson == null) {
      return false;
    }
    try {
      JsonNode n1 = objectMapper.readTree(actualJson);
      JsonNode n2 = objectMapper.readTree(expectedJson);
      return Objects.equals(n1, n2);
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  private IssueRun checkExactSubmitReplay(
      IssueRun existing, UUID expectedRunId, String expectedResultJson) {
    if (existing.getId().equals(expectedRunId)
        && existing.getRole() == IssueRunRole.EXECUTOR
        && existing.getStatus() == IssueRunStatus.COMPLETED
        && existing.getOutcome() == IssueRunOutcome.SUBMITTED
        && checkJsonEquivalent(existing.getResult(), expectedResultJson)) {
      return existing;
    }
    throw new AiValidationException("issue_run", "Terminal action ID conflict");
  }

  private IssueRun checkExactReviewReplay(
      IssueRun existing,
      UUID expectedRunId,
      String expectedReviewerAgentName,
      IssueRunOutcome expectedOutcome,
      String expectedResultJson) {
    if (!existing.getId().equals(expectedRunId)
        || existing.getRole() != IssueRunRole.REVIEWER
        || existing.getStatus() != IssueRunStatus.COMPLETED
        || existing.getOutcome() != expectedOutcome
        || !Objects.equals(existing.getAgentName(), expectedReviewerAgentName)
        || !checkJsonEquivalent(existing.getResult(), expectedResultJson)) {
      throw new AiValidationException("issue_run", "Terminal action ID conflict");
    }
    return existing;
  }

  private boolean isTerminalActionUniqueConflict(DataIntegrityViolationException e) {
    Throwable root = e.getRootCause();
    if (root instanceof PSQLException pe) {
      String constraint =
          pe.getServerErrorMessage() != null ? pe.getServerErrorMessage().getConstraint() : null;
      if ("uk_issue_run_terminal_action".equals(constraint)) {
        return true;
      }
    }
    String message = e.getMessage();
    return message != null && message.contains("uk_issue_run_terminal_action");
  }
}
