package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueAgentSessionOwnershipMapper;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueAgentSessionOwnershipRepository
    implements IssueAgentSessionOwnershipRepository {

  private final IssueAgentSessionOwnershipMapper issueAgentSessionOwnershipMapper;

  @Override
  public boolean insert(UUID sessionId, UUID issueAgentSessionId) {
    return issueAgentSessionOwnershipMapper.insert(sessionId, issueAgentSessionId) == 1;
  }

  @Override
  public UUID findAgentSessionIdBySessionId(UUID sessionId) {
    return issueAgentSessionOwnershipMapper.findAgentSessionIdBySessionId(sessionId);
  }

  @Override
  public List<UUID> listSessionIds(UUID issueAgentSessionId) {
    List<UUID> sessionIds = issueAgentSessionOwnershipMapper.listSessionIds(issueAgentSessionId);
    return sessionIds == null ? List.of() : List.copyOf(sessionIds);
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    return issueAgentSessionOwnershipMapper.deleteBySessionId(sessionId);
  }
}
