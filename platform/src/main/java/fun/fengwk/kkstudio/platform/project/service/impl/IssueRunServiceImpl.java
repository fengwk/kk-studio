package fun.fengwk.kkstudio.platform.project.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
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
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueRunServiceImpl implements IssueRunService {

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
    if (agentName == null || agentName.isBlank()) {
      throw new AiValidationException("issue_run", "agentName must not be blank for executor run");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (issue.getStatus() != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start run: issue must be in TODO status, current is " + issue.getStatus());
    }
    if (issue.getAssigneeAgentName() == null
        || !issue.getAssigneeAgentName().equals(agentName.trim())) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start run: agentName mismatch with issue assignee ("
              + issue.getAssigneeAgentName()
              + ")");
    }

    // 检查是否被未完成的依赖阻塞
    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issueId);
    for (IssueDependency dep : deps) {
      Issue depIssue = issueRepository.getById(dep.getDependsOnIssueId());
      if (depIssue == null || depIssue.getStatus() != IssueStatus.DONE) {
        throw new AiValidationException(
            "issue_run",
            "Cannot start run: issue is blocked by dependency " + dep.getDependsOnIssueId());
      }
    }

    IssueRun activeRun = issueRunRepository.findActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: an active run already exists for issue " + issueId);
    }

    long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
    UUID runId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName(agentName.trim())
            .submissionRunId(null)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedSpecRevision(issue.getSpecRevision())
            .observedInputSequence(issue.getInputSequence())
            .continuationCount(0)
            .maxContinuations(maxContinuations > 0 ? maxContinuations : 10)
            .deadline(deadline)
            .waitingReason(null)
            .result(null)
            .terminalActionId(null)
            .version(0L)
            .build();

    issueRunRepository.create(run);

    issue.setStatus(IssueStatus.IN_PROGRESS);
    issueRepository.updateById(issue, issue.getVersion());

    controllerWorkStore.requestWork(issueId, Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueRun startReviewerRun(
      UUID issueId, String agentName, Instant deadline, int maxContinuations) {
    Objects.requireNonNull(issueId, "issueId");
    if (agentName == null || agentName.isBlank()) {
      throw new AiValidationException("issue_run", "agentName must not be blank for reviewer run");
    }

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start reviewer run: issue must be in IN_REVIEW status, current is "
              + issue.getStatus());
    }
    if (issue.getReviewerAgentName() == null
        || !issue.getReviewerAgentName().equals(agentName.trim())) {
      throw new AiValidationException(
          "issue_run",
          "Cannot start reviewer run: agentName mismatch with issue reviewer ("
              + issue.getReviewerAgentName()
              + ")");
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
      throw new AiValidationException(
          "issue_run", "No submitted executor run found to review for issue " + issueId);
    }

    IssueRun activeRun = issueRunRepository.findActiveByIssueId(issueId);
    if (activeRun != null) {
      throw new AiValidationException(
          "issue_run", "Cannot start run: an active run already exists for issue " + issueId);
    }

    long ordinal = issueRunRepository.allocateNextOrdinal(issueId);
    UUID runId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(ordinal)
            .role(IssueRunRole.REVIEWER)
            .actorType(IssueRunActorType.AGENT)
            .agentName(agentName.trim())
            .submissionRunId(submissionRunId)
            .status(IssueRunStatus.RUNNING)
            .outcome(null)
            .observedSpecRevision(issue.getSpecRevision())
            .observedInputSequence(issue.getInputSequence())
            .continuationCount(0)
            .maxContinuations(maxContinuations > 0 ? maxContinuations : 10)
            .deadline(deadline)
            .waitingReason(null)
            .result(null)
            .terminalActionId(null)
            .version(0L)
            .build();

    issueRunRepository.create(run);
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
    if (terminalActionId == null || terminalActionId.isBlank()) {
      throw new AiValidationException("issue_run", "terminalActionId must not be blank for submit");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (run.getRole() != IssueRunRole.EXECUTOR || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Only RUNNING EXECUTOR run can be submitted, current role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    Issue issue = issueRepository.lockById(run.getIssueId());
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", run.getIssueId().toString());
    }
    if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_PROGRESS for submit, current status=" + issue.getStatus());
    }

    // 围栏校验：observed cursors 必须与当前 issue 一致
    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException(
          "issue_run",
          "Submit rejected due to stale cursors: observed (spec="
              + observedSpecRevision
              + ", input="
              + observedInputSequence
              + ") vs current (spec="
              + issue.getSpecRevision()
              + ", input="
              + issue.getInputSequence()
              + ")");
    }

    // 依赖校验：所有前提依赖必须仍为 DONE
    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issue.getId());
    for (IssueDependency dep : deps) {
      Issue depIssue = issueRepository.getById(dep.getDependsOnIssueId());
      if (depIssue == null || depIssue.getStatus() != IssueStatus.DONE) {
        throw new AiValidationException(
            "issue_run", "Cannot submit: dependency " + dep.getDependsOnIssueId() + " is not DONE");
      }
    }

    run.setStatus(IssueRunStatus.COMPLETED);
    run.setOutcome(IssueRunOutcome.SUBMITTED);
    run.setTerminalActionId(terminalActionId.trim());
    run.setResult(toJson(summary, verification));
    run.setCompletedAt(Instant.now());

    issueRunRepository.updateById(run, run.getVersion());

    issue.setStatus(IssueStatus.IN_REVIEW);
    issueRepository.updateById(issue, issue.getVersion());

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
    if (question == null || question.isBlank()) {
      throw new AiValidationException(
          "issue_run", "question must not be blank when requesting input");
    }

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (run.getRole() != IssueRunRole.EXECUTOR || run.getStatus() != IssueRunStatus.RUNNING) {
      throw new AiValidationException(
          "issue_run",
          "Only RUNNING EXECUTOR run can request input, current role="
              + run.getRole()
              + ", status="
              + run.getStatus());
    }

    Issue issue = issueRepository.lockById(run.getIssueId());
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", run.getIssueId().toString());
    }
    if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_PROGRESS to request input, current is " + issue.getStatus());
    }

    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException(
          "issue_run",
          "Request input rejected due to stale cursors: observed (spec="
              + observedSpecRevision
              + ", input="
              + observedInputSequence
              + ") vs current (spec="
              + issue.getSpecRevision()
              + ", input="
              + issue.getInputSequence()
              + ")");
    }

    run.setStatus(IssueRunStatus.WAITING_HUMAN);
    run.setWaitingReason(question.trim());
    issueRunRepository.updateById(run, run.getVersion());

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

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (issue.getStatus() != IssueStatus.IN_REVIEW) {
      throw new AiValidationException(
          "issue_run",
          "Issue must be in IN_REVIEW to be reviewed, current is " + issue.getStatus());
    }

    if (observedSpecRevision != issue.getSpecRevision()
        || observedInputSequence != issue.getInputSequence()) {
      throw new AiValidationException(
          "issue_run",
          "Review rejected due to stale cursors: observed (spec="
              + observedSpecRevision
              + ", input="
              + observedInputSequence
              + ") vs current (spec="
              + issue.getSpecRevision()
              + ", input="
              + issue.getInputSequence()
              + ")");
    }

    IssueRun finalReviewerRun;
    if (actorType == IssueRunActorType.AGENT) {
      Objects.requireNonNull(runId, "runId is required for AGENT review");
      if (terminalActionId == null || terminalActionId.isBlank()) {
        throw new AiValidationException(
            "issue_run", "terminalActionId must not be blank for agent review");
      }
      IssueRun run = issueRunRepository.lockById(runId);
      if (run == null) {
        throw new AiResourceNotFoundException("issue_run", runId.toString());
      }
      if (run.getRole() != IssueRunRole.REVIEWER || run.getStatus() != IssueRunStatus.RUNNING) {
        throw new AiValidationException(
            "issue_run",
            "Agent review requires a RUNNING REVIEWER run, found role="
                + run.getRole()
                + ", status="
                + run.getStatus());
      }

      run.setStatus(IssueRunStatus.COMPLETED);
      run.setOutcome(
          decision == ReviewDecision.APPROVE
              ? IssueRunOutcome.APPROVED
              : IssueRunOutcome.CHANGES_REQUESTED);
      run.setTerminalActionId(terminalActionId.trim());
      run.setResult(toJson(summary, verification));
      run.setCompletedAt(Instant.now());

      issueRunRepository.updateById(run, run.getVersion());
      finalReviewerRun = issueRunRepository.getById(runId);
    } else {
      // HUMAN 评审：仅当 reviewerAgentName 为空时允许
      if (issue.getReviewerAgentName() != null) {
        throw new AiValidationException(
            "issue_run",
            "Human review is not permitted when reviewerAgentName is configured ("
                + issue.getReviewerAgentName()
                + ")");
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
        throw new AiValidationException(
            "issue_run", "No submitted executor run found to review for issue " + issueId);
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
              .outcome(
                  decision == ReviewDecision.APPROVE
                      ? IssueRunOutcome.APPROVED
                      : IssueRunOutcome.CHANGES_REQUESTED)
              .observedSpecRevision(observedSpecRevision)
              .observedInputSequence(observedInputSequence)
              .continuationCount(0)
              .maxContinuations(0)
              .terminalActionId(
                  terminalActionId != null && !terminalActionId.isBlank()
                      ? terminalActionId.trim()
                      : "human_review:" + UUID.randomUUID())
              .result(toJson(summary, verification))
              .version(0L)
              .completedAt(Instant.now())
              .build();

      issueRunRepository.create(humanRun);
      finalReviewerRun = issueRunRepository.getById(humanRunId);
    }

    if (decision == ReviewDecision.APPROVE) {
      issue.setStatus(IssueStatus.DONE);
      issueRepository.updateById(issue, issue.getVersion());
    } else {
      // REQUEST_CHANGES: 自动追加 REVIEW_FEEDBACK 并递增 inputSequence，Issue 退回 TODO
      long newSeq = issueRepository.incrementInputSequence(issueId);
      IssueInput feedback =
          IssueInput.builder()
              .issueId(issueId)
              .sequence(newSeq)
              .kind(IssueInputKind.REVIEW_FEEDBACK)
              .body(
                  summary != null && !summary.isBlank()
                      ? summary.trim()
                      : "Changes requested by review")
              .idempotencyKey(null)
              .build();
      issueInputRepository.append(feedback);

      // 重新读取最新版本
      Issue freshIssue = issueRepository.lockById(issueId);
      freshIssue.setStatus(IssueStatus.TODO);
      issueRepository.updateById(freshIssue, freshIssue.getVersion());
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

    IssueRun run = issueRunRepository.lockById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }

    run.setStatus(terminalStatus);
    run.setWaitingReason(waitingReason);
    run.setCompletedAt(Instant.now());

    issueRunRepository.updateById(run, run.getVersion());

    // FAILED / UNKNOWN 不改变 Issue status！
    controllerWorkStore.requestWork(run.getIssueId(), Instant.now());
    return issueRunRepository.getById(runId);
  }

  @Transactional
  @Override
  public IssueInput retryRun(UUID issueId, String idempotencyKey) {
    Objects.requireNonNull(issueId, "issueId");

    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
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

    String trimmedKey =
        idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
    if (trimmedKey != null) {
      IssueInput existing = issueInputRepository.findByIdempotencyKey(issueId, trimmedKey);
      if (existing != null) {
        return existing;
      }
    }

    long newSequence = issueRepository.incrementInputSequence(issueId);
    IssueInput retryInput =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(newSequence)
            .kind(IssueInputKind.RETRY)
            .body("Retry requested for run " + latestRun.getId())
            .idempotencyKey(trimmedKey)
            .build();
    issueInputRepository.append(retryInput);

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
    IssueRun run = issueRunRepository.getById(runId);
    if (run == null) {
      throw new AiResourceNotFoundException("issue_run", runId.toString());
    }
    if (run.getActorType() == IssueRunActorType.HUMAN) {
      throw new AiValidationException(
          "issue_run_session", "Human run cannot be bound to a harness session");
    }
    issueRunSessionRepository.bindSession(runId, sessionId);
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
    try {
      Map<String, String> map = new HashMap<>();
      if (summary != null) {
        map.put("summary", summary);
      }
      if (verification != null) {
        map.put("verification", verification);
      }
      return objectMapper.writeValueAsString(map);
    } catch (Exception e) {
      return "{}";
    }
  }
}
