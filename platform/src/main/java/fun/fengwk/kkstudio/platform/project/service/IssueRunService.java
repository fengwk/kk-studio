package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IssueRunService {

  IssueRun startExecutorRun(UUID issueId, String agentName, Instant deadline, int maxContinuations);

  IssueRun startReviewerRun(UUID issueId, String agentName, Instant deadline, int maxContinuations);

  IssueRun completeExecutorRun(
      UUID runId, String terminalActionId, String summary, String verification);

  IssueRun requestInput(UUID runId, String question, String context);

  IssueRun reviewByAgent(
      UUID runId,
      String reviewerAgentName,
      String terminalActionId,
      ReviewDecision decision,
      String reason);

  void reviewByHuman(UUID issueId, ReviewDecision decision, String reason, String idempotencyKey);

  IssueRun failRun(UUID runId, IssueRunStatus terminalStatus, String waitingReason);

  IssueActivity retryRun(UUID issueId, String idempotencyKey);

  IssueRun getRun(UUID runId);

  IssueRun getActiveRun(UUID issueId);

  IssueRun getLatestRun(UUID issueId);

  List<IssueRun> listRuns(UUID issueId);

  IssueAgentSession getAgentSession(UUID issueId, String agentName);

  IssueAgentSession findAgentSession(UUID sessionId);

  IssueAgentSession findAgentSessionByThreadId(UUID threadId);
}
