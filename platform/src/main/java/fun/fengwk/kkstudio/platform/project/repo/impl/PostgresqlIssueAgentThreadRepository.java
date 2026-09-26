package fun.fengwk.kkstudio.platform.project.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueAgentThreadMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueAgentThreadDO;

import java.util.Objects;
import java.util.UUID;

/** 基于 PostgreSQL 的 Issue+Agent 稳定 Thread 绑定仓库。 */
@Repository
public class PostgresqlIssueAgentThreadRepository implements IssueAgentThreadRepository {

  private final IssueAgentThreadMapper mapper;

  public PostgresqlIssueAgentThreadRepository(IssueAgentThreadMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public IssueAgentThread findByIssueIdAndAgentName(UUID issueId, String agentName) {
    return toModel(mapper.findByIssueIdAndAgentName(issueId, agentName));
  }

  @Override
  public boolean insert(IssueAgentThread binding) {
    Objects.requireNonNull(binding, "binding");
    return mapper.insert(binding.issueId(), binding.agentName(), binding.threadId()) == 1;
  }

  @Override
  public int deleteByIssueIdAndAgentName(UUID issueId, String agentName) {
    return mapper.deleteByIssueIdAndAgentName(issueId, agentName);
  }

  private static IssueAgentThread toModel(IssueAgentThreadDO row) {
    if (row == null) {
      return null;
    }
    return new IssueAgentThread(row.getIssueId(), row.getAgentName(), row.getThreadId());
  }
}
