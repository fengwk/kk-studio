package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;

import java.util.UUID;

public interface IssueRunSessionRepository {

  boolean bindSession(UUID runId, UUID sessionId);

  IssueRunSession findByRunId(UUID runId);

  IssueRunSession findBySessionId(UUID sessionId);

  boolean deleteByRunId(UUID runId);
}
