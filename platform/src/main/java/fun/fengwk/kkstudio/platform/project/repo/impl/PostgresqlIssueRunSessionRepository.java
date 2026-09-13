package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueRunSessionMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueRunSessionDO;

import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueRunSessionRepository implements IssueRunSessionRepository {

  private final IssueRunSessionMapper issueRunSessionMapper;

  @Override
  public boolean bindSession(UUID runId, UUID sessionId) {
    return issueRunSessionMapper.insert(runId, sessionId) == 1;
  }

  @Override
  public IssueRunSession findByRunId(UUID runId) {
    return toModel(issueRunSessionMapper.findByRunId(runId));
  }

  @Override
  public IssueRunSession findBySessionId(UUID sessionId) {
    return toModel(issueRunSessionMapper.findBySessionId(sessionId));
  }

  @Override
  public boolean deleteByRunId(UUID runId) {
    return issueRunSessionMapper.deleteByRunId(runId) == 1;
  }

  private IssueRunSession toModel(IssueRunSessionDO row) {
    if (row == null) {
      return null;
    }
    return IssueRunSession.builder()
        .runId(row.getRunId())
        .sessionId(row.getSessionId())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
