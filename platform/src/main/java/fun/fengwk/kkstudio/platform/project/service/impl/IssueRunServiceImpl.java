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
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatusTransition;
import fun.fengwk.kkstudio.platform.project.model.IssueTransitionAction;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueRunServiceImpl implements IssueRunService {

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueInputRepository issueInputRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueRunSessionRepository issueRunSessionRepository;
  private final IssueControllerWorkStore controllerWorkStore;
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

    // 1. 预读 Issue 获取不可变 projectId
    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    UUID projectId = initialIssue.getProjectId();

    // 2. 锁 Project（拒绝 archived）
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project", projectId.toString());
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot start run in an archived project");
    }

    // 3. 锁 Issue
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
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

    // 4. Dependency fence：按 UUID 稳定顺序 FOR UPDATE 锁所有 dependency Issue 并检查均为 DONE
    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issueId);
    List<UUID> depIssueIds =
        deps.stream().map(IssueDependency::getDependsOnIssueId).sorted().toList();
    for (UUID depId : depIssueIds) {
      Issue depIssue = issueRepository.lockById(depId);
      if (depIssue == null || depIssue.getStatus() != IssueStatus.DONE) {
        throw new AiValidationException("issue_run", "Cannot start run: dependency is not DONE");
      }
    }

    // 5. 锁 active Run 保证单活跃
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: an active run already exists for issue");
    }

    long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
    UUID runId = UUID.randomUUID();
    int continuationLimit = maxContinuations;
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName(trimmedAgent)
            .submissionRunId(null)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedSpecRevision(issue.getSpecRevision())
            .observedInputSequence(issue.getInputSequence())
            .continuationCount(0)
            .maxContinuations(continuationLimit)
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

    controllerWorkStore.requestWork(issueId, Instant.now());
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

    // 1. 预读 Issue 获取不可变 projectId
    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    UUID projectId = initialIssue.getProjectId();

    // 2. 锁 Project（拒绝 archived）
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project", projectId.toString());
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot start run in an archived project");
    }

    // 3. 锁 Issue
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
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

    // 寻找该 Issue 最近一次 SUBMITTED 的 Executor Run 作为 submissionRunId
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
    int continuationLimit = maxContinuations;
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.REVIEWER)
            .actorType(IssueRunActorType.AGENT)
            .agentName(trimmedAgent)
            .submissionRunId(submissionRunId)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedSpecRevision(issue.getSpecRevision())
            .observedInputSequence(issue.getInputSequence())
            .continuationCount(0)
            .maxContinuations(continuationLimit)
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

    controllerWorkStore.requestWork(issueId, Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun submitRun(
      UUID runId,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      String summary,
      String verification) {
    Objects.requireNonNull(runId, "runId");
    String trimmedActionId =
        ProjectValidationUtils.trimAndValidate(terminalActionId, "terminalActionId", 255, true);

    String resultJson = toJson(summary, verification);

    // 幂等检查：若已有相同 terminalActionId，执行 exact replay 检查
    IssueRun existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactSubmitReplay(
          existingByAction, runId, observedSpecRevision, observedInputSequence, resultJson);
    }

    // 仅有 runId 的命令先无锁读取 Run 的不可变 issueId，获取 projectId，再锁 Project，再锁 Issue，最后锁 Run
    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    // 锁内再次检查 actionId，关闭 precheck 与写入之间竞态
    existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactSubmitReplay(
          existingByAction, runId, observedSpecRevision, observedInputSequence, resultJson);
    }

    if (run.isTerminal()) {
      throw new AiValidationException(
          "issue_run", "Run is already terminal and cannot be submitted");
    }

    if (run.getRole() != IssueRunRole.EXECUTOR || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Only RUNNING EXECUTOR run can be submitted, current role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_PROGRESS for submit, current status=" + issue.getStatus());
    }

    // 围栏校验：observed cursors 必须与当前 issue 一致
    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException("issue_run", "Submit rejected due to stale cursors");
    }

    // Dependency fence：按 UUID 稳定顺序 FOR UPDATE 锁所有 dependency Issue 并检查均为 DONE
    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issue.getId());
    List<UUID> depIssueIds =
        deps.stream().map(IssueDependency::getDependsOnIssueId).sorted().toList();
    for (UUID depId : depIssueIds) {
      Issue depIssue = issueRepository.lockById(depId);
      if (depIssue == null || depIssue.getStatus() != IssueStatus.DONE) {
        throw new AiValidationException("issue_run", "Cannot submit: dependency is not DONE");
      }
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

    controllerWorkStore.requestWork(issue.getId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun requestInput(
      UUID runId,
      long observedSpecRevision,
      long observedInputSequence,
      String question,
      String context) {
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

    // 仅有 runId 的命令先无锁读取 Run 的不可变 issueId，获取 projectId，再锁 Project，再锁 Issue，最后锁 Run
    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    if (run.getRole() != IssueRunRole.EXECUTOR || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Only RUNNING EXECUTOR run can request input, current role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_PROGRESS to request input, current is " + issue.getStatus());
    }

    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException("issue_run", "Request input rejected due to stale cursors");
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

    controllerWorkStore.requestWork(issue.getId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun reviewRun(
      UUID issueId,
      UUID runId,
      IssueRunActorType actorType,
      String reviewerAgentName,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      ReviewDecision decision,
      String summary,
      String verification) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(actorType, "actorType");
    Objects.requireNonNull(decision, "decision");
    String trimmedActionId =
        ProjectValidationUtils.trimAndValidate(terminalActionId, "terminalActionId", 255, true);

    String trimmedReviewerAgentName;
    if (actorType == IssueRunActorType.AGENT) {
      if (runId == null) {
        throw new AiValidationException("issue_run", "runId is required for AGENT review");
      }
      trimmedReviewerAgentName =
          ProjectValidationUtils.trimAndValidate(reviewerAgentName, "reviewerAgentName", 128, true);
    } else {
      if (runId != null) {
        throw new AiValidationException("issue_run", "runId must not be provided for human review");
      }
      if (reviewerAgentName != null && !reviewerAgentName.isBlank()) {
        throw new AiValidationException(
            "issue_run", "reviewerAgentName must not be provided for human review");
      }
      trimmedReviewerAgentName = null;
    }

    String resultJson = toJson(summary, verification);
    IssueRunOutcome expectedOutcome =
        decision == ReviewDecision.APPROVE
            ? IssueRunOutcome.APPROVED
            : IssueRunOutcome.CHANGES_REQUESTED;

    // 幂等检查：若已有相同 terminalActionId，执行 exact replay 检查
    IssueRun existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactReviewReplay(
          existingByAction,
          issueId,
          runId,
          actorType,
          trimmedReviewerAgentName,
          expectedOutcome,
          observedSpecRevision,
          observedInputSequence,
          resultJson);
    }

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    // 锁内再次检查 actionId，关闭 precheck 与写入之间竞态
    existingByAction = issueRunRepository.findByTerminalActionId(trimmedActionId);
    if (existingByAction != null) {
      return checkExactReviewReplay(
          existingByAction,
          issueId,
          runId,
          actorType,
          trimmedReviewerAgentName,
          expectedOutcome,
          observedSpecRevision,
          observedInputSequence,
          resultJson);
    }

    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_REVIEW to be reviewed, current is " + issue.getStatus());
    }

    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException("issue_run", "Review rejected due to stale cursors");
    }

    IssueRun finalReviewerRun;
    if (actorType == IssueRunActorType.AGENT) {
      IssueRun run = issueRunRepository.lockById(runId);
      if (run == null) {
        throw new AiResourceNotFoundException("issue_run", runId.toString());
      }
      if (!run.getIssueId().equals(issueId)) {
        throw new AiValidationException("issue_run", "Run does not belong to the target issue");
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

      // agent review 必须校验 run.issueId、role/status、run.agentName 与 issue.reviewerAgentName/入参身份一致
      String expectedAgent = issue.getReviewerAgentName();
      if (expectedAgent == null
          || !expectedAgent.equals(run.getAgentName())
          || !expectedAgent.equals(trimmedReviewerAgentName)) {
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
      finalReviewerRun = issueRunRepository.getById(runId);
    } else {
      if (issue.getReviewerAgentName() != null) {
        throw new AiValidationException(
            "issue_run", "Human review is not permitted when reviewerAgentName is configured");
      }

      // 寻找该 Issue 最近一次 SUBMITTED 的 Executor Run
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

      long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
      UUID humanRunId = UUID.randomUUID();
      IssueRun humanRun =
          IssueRun.builder()
              .id(humanRunId)
              .issueId(issueId)
              .ordinal(ordinal)
              .role(IssueRunRole.REVIEWER)
              .actorType(IssueRunActorType.HUMAN)
              .agentName(null)
              .submissionRunId(submissionRunId)
              .status(IssueRunStatus.COMPLETED)
              .outcome(expectedOutcome)
              .observedSpecRevision(observedSpecRevision)
              .observedInputSequence(observedInputSequence)
              .continuationCount(0)
              .maxContinuations(0)
              .terminalActionId(trimmedActionId)
              .result(resultJson)
              .waitingReason(null)
              .version(0L)
              .completedAt(Instant.now())
              .build();

      try {
        boolean runCreated = issueRunRepository.create(humanRun);
        if (!runCreated) {
          throw new AiValidationException("issue_run", "Failed to create human reviewer run");
        }
      } catch (DataIntegrityViolationException e) {
        if (isTerminalActionUniqueConflict(e)) {
          throw new AiValidationException("issue_run", "Terminal action ID conflict");
        }
        throw e;
      }
      finalReviewerRun = issueRunRepository.getById(humanRunId);
    }

    if (decision == ReviewDecision.APPROVE) {
      issue.setStatus(
          IssueStatusTransition.transition(issue.getStatus(), IssueTransitionAction.APPROVE));
      boolean issueUpdated = issueRepository.updateById(issue, issue.getVersion());
      if (!issueUpdated) {
        throw new AiValidationException("issue", "Failed to update issue status to DONE");
      }
    } else {
      // REQUEST_CHANGES 追加 input 与状态迁移保持同一事务并检查每次 mutation 结果
      long newSeq = issueRepository.incrementInputSequence(issueId);
      if (newSeq <= 0) {
        throw new AiValidationException("issue", "Failed to increment input sequence");
      }
      String feedbackBody =
          summary != null && !summary.isBlank() ? summary.trim() : "Changes requested by review";
      ProjectValidationUtils.validateUtf8Bytes(feedbackBody, "body", 1048576, true);
      IssueInput feedback =
          IssueInput.builder()
              .issueId(issueId)
              .sequence(newSeq)
              .kind(IssueInputKind.REVIEW_FEEDBACK)
              .body(feedbackBody)
              .idempotencyKey(null)
              .build();
      boolean appended = issueInputRepository.append(feedback);
      if (!appended) {
        throw new AiValidationException("issue_input", "Failed to append review feedback input");
      }

      // 重新读取最新行版本
      Issue freshIssue = issueRepository.lockById(issueId);
      freshIssue.setStatus(
          IssueStatusTransition.transition(
              freshIssue.getStatus(), IssueTransitionAction.REQUEST_CHANGES));
      boolean issueUpdated = issueRepository.updateById(freshIssue, freshIssue.getVersion());
      if (!issueUpdated) {
        throw new AiValidationException("issue", "Failed to update issue status to TODO");
      }
    }

    controllerWorkStore.requestWork(issueId, Instant.now());
    return finalReviewerRun;
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

    // 仅有 runId 的命令先无锁读取 Run 的不可变 issueId，获取 projectId，再锁 Project，再锁 Issue，最后锁 Run
    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
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

    // FAILED / UNKNOWN 不改变 Issue status！
    controllerWorkStore.requestWork(run.getIssueId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueInput retryRun(UUID issueId, String idempotencyKey) {
    Objects.requireNonNull(issueId, "issueId");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new AiValidationException("issue_run", "idempotencyKey must not be blank for retry");
    }
    String trimmedKey =
        ProjectValidationUtils.trimAndValidate(idempotencyKey, "idempotencyKey", 128, true);

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue_run", "Cannot retry archived issue");
    }
    if (issue.isTerminal()) {
      throw new AiValidationException("issue_run", "Cannot retry terminal issue");
    }

    // 幂等重放检查：同 key replay 不重复递增
    IssueInput existing = issueInputRepository.findByIdempotencyKey(issueId, trimmedKey);
    if (existing != null) {
      return existing;
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

    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot retry while an active run exists for issue " + issueId);
    }

    long newSequence = issueRepository.incrementInputSequence(issueId);
    if (newSequence <= 0) {
      throw new AiValidationException("issue", "Failed to increment input sequence");
    }
    IssueInput retryInput =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(newSequence)
            .kind(IssueInputKind.RETRY)
            .body("Retry requested for run " + latestRun.getId())
            .idempotencyKey(trimmedKey)
            .build();

    boolean appended = issueInputRepository.append(retryInput);
    if (!appended) {
      throw new AiValidationException("issue_input", "Failed to append retry input");
    }

    controllerWorkStore.requestWork(issueId, Instant.now());
    return retryInput;
  }

  @Override
  public IssueRun getRun(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
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

  @Transactional
  @Override
  public void bindSession(UUID runId, UUID sessionId) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(sessionId, "sessionId");

    // 仅有 runId 的命令先无锁读取 Run 的不可变 issueId，获取 projectId，再锁 Project，再锁 Issue，最后锁 Run
    IssueRun initialRun = issueRunRepository.getById(runId);
    if (initialRun == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    UUID issueId = initialRun.getIssueId();

    Issue initialIssue = issueRepository.getById(issueId);
    if (initialIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    projectRepository.lockById(initialIssue.getProjectId());

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (!issueId.equals(run.getIssueId())) {
      throw new AiValidationException("issue_run", "Run issue mismatch");
    }

    // 同 relation 同 session 幂等：已有相同 relation 的 replay 可返回
    IssueRunSession existingForRun = issueRunSessionRepository.findByRunId(runId);
    if (existingForRun != null) {
      if (existingForRun.getSessionId().equals(sessionId)) {
        return;
      }
      throw new AiValidationException(
          "issue_run_session", "Run is already bound to a different session");
    }

    IssueRunSession existingForSession = issueRunSessionRepository.findBySessionId(sessionId);
    if (existingForSession != null) {
      throw new AiValidationException(
          "issue_run_session", "Session is already bound to another run");
    }

    // 新 Run session 只允许 AGENT active Run
    if (run.getActorType() != IssueRunActorType.AGENT) {
      throw new AiValidationException(
          "issue_run_session", "Only AGENT run can be bound to a harness session");
    }
    if (!run.getStatus().isActive()) {
      throw new AiValidationException(
          "issue_run_session", "Cannot bind session to inactive run, status is " + run.getStatus());
    }

    try {
      boolean bound = issueRunSessionRepository.bindSession(runId, sessionId);
      if (!bound) {
        throw new AiValidationException("issue_run_session", "Failed to bind run session");
      }
    } catch (DataIntegrityViolationException e) {
      if (isSingleOwnerConflict(e)) {
        throw new AiValidationException(
            "issue_run_session", "Session is already owned by another entity");
      }
      throw e;
    }
  }

  @Override
  public IssueRunSession getRunSession(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    return issueRunSessionRepository.findByRunId(runId);
  }

  @Override
  public IssueRunSession findRunSession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return issueRunSessionRepository.findBySessionId(sessionId);
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
      // PostgreSQL jsonb::text adds one space after each object colon and comma. Values are
      // strings, so this flat result object has exactly (2 * fieldCount - 1) formatting bytes.
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
      IssueRun existing,
      UUID expectedRunId,
      long expectedSpecRevision,
      long expectedInputSequence,
      String expectedResultJson) {
    if (existing.getId().equals(expectedRunId)
        && existing.getRole() == IssueRunRole.EXECUTOR
        && existing.getStatus() == IssueRunStatus.COMPLETED
        && existing.getOutcome() == IssueRunOutcome.SUBMITTED
        && existing.getObservedSpecRevision() == expectedSpecRevision
        && existing.getObservedInputSequence() == expectedInputSequence
        && checkJsonEquivalent(existing.getResult(), expectedResultJson)) {
      return existing;
    }
    throw new AiValidationException("issue_run", "Terminal action ID conflict");
  }

  private IssueRun checkExactReviewReplay(
      IssueRun existing,
      UUID expectedIssueId,
      UUID expectedRunId,
      IssueRunActorType expectedActorType,
      String expectedReviewerAgentName,
      IssueRunOutcome expectedOutcome,
      long expectedSpecRevision,
      long expectedInputSequence,
      String expectedResultJson) {
    if (!existing.getIssueId().equals(expectedIssueId)
        || existing.getRole() != IssueRunRole.REVIEWER
        || existing.getActorType() != expectedActorType
        || existing.getStatus() != IssueRunStatus.COMPLETED
        || existing.getOutcome() != expectedOutcome
        || existing.getObservedSpecRevision() != expectedSpecRevision
        || existing.getObservedInputSequence() != expectedInputSequence
        || !checkJsonEquivalent(existing.getResult(), expectedResultJson)) {
      throw new AiValidationException("issue_run", "Terminal action ID conflict");
    }
    if (expectedActorType == IssueRunActorType.AGENT) {
      if (expectedRunId == null
          || !existing.getId().equals(expectedRunId)
          || !Objects.equals(existing.getAgentName(), expectedReviewerAgentName)) {
        throw new AiValidationException("issue_run", "Terminal action ID conflict");
      }
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

  private boolean isSingleOwnerConflict(DataIntegrityViolationException e) {
    Throwable root = e.getRootCause();
    if (root instanceof PSQLException pe) {
      String constraint =
          pe.getServerErrorMessage() != null ? pe.getServerErrorMessage().getConstraint() : null;
      if ("chk_harness_session_single_owner".equals(constraint)
          || "pk_harness_session_owner_guard".equals(constraint)
          || "uk_issue_run_session_session".equals(constraint)) {
        return true;
      }
    }
    String message = e.getMessage();
    return message != null
        && (message.contains("chk_harness_session_single_owner")
            || message.contains("pk_harness_session_owner_guard")
            || message.contains("uk_issue_run_session_session"));
  }
}
