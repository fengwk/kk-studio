package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IssueRunService {

  IssueRun startExecutorRun(UUID issueId, String agentName, Instant deadline, int maxContinuations);

  IssueRun startReviewerRun(UUID issueId, String agentName, Instant deadline, int maxContinuations);

  IssueRun submitRun(
      UUID runId,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      String summary,
      String verification);

  IssueRun requestInput(
      UUID runId,
      long observedSpecRevision,
      long observedInputSequence,
      String question,
      String context);

  IssueRun reviewRun(
      UUID issueId,
      UUID runId,
      IssueRunActorType actorType,
      String reviewerAgentName,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      ReviewDecision decision,
      String summary,
      String verification);

  IssueRun failRun(UUID runId, IssueRunStatus terminalStatus, String waitingReason);

  IssueInput retryRun(UUID issueId, String idempotencyKey);

  IssueRun getRun(UUID runId);

  IssueRun getActiveRun(UUID issueId);

  IssueRun getLatestRun(UUID issueId);

  List<IssueRun> listRuns(UUID issueId);

  IssueRunSession getRunSession(UUID runId);

  IssueRunSession findRunSession(UUID sessionId);
}
