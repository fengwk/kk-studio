package fun.fengwk.kkstudio.project.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueAgentThreadDO;
import fun.fengwk.kkstudio.project.repo.impl.store.IssueAgentThreadJdbcStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 基于 PostgreSQL 的 Issue+Agent 稳定 Thread 绑定仓库。 */
@Repository
public class PostgresqlIssueAgentThreadRepository implements IssueAgentThreadRepository {

  private final IssueAgentThreadJdbcStore store;
  private final PostgresqlProjectChangeNotifier notifier;

  public PostgresqlIssueAgentThreadRepository(
      IssueAgentThreadJdbcStore store, PostgresqlProjectChangeNotifier notifier) {
    this.store = Objects.requireNonNull(store, "store");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  @Override
  public IssueAgentThread findByIssueIdAndAgentName(UUID issueId, String agentName) {
    return toModel(store.findByIssueIdAndAgentName(issueId, agentName));
  }

  @Override
  public IssueAgentThread findByThreadId(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return toModel(store.findByThreadId(threadId));
  }

  @Override
  public List<IssueAgentThread> listByIssueId(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return store.listByIssueId(issueId).stream()
        .map(PostgresqlIssueAgentThreadRepository::toModel)
        .toList();
  }

  @Override
  public boolean insert(IssueAgentThread binding) {
    Objects.requireNonNull(binding, "binding");
    PostgresqlProjectChangeNotifier.requireTransaction();
    boolean changed = store.insert(binding.issueId(), binding.agentName(), binding.threadId()) == 1;
    if (changed) {
      notifier.issueChanged(binding.issueId());
    }
    return changed;
  }

  @Override
  public int deleteByIssueIdAndAgentName(UUID issueId, String agentName) {
    PostgresqlProjectChangeNotifier.requireTransaction();
    int changed = store.deleteByIssueIdAndAgentName(issueId, agentName);
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  @Override
  public boolean isIssueAgentBranch(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.isIssueAgentBranch(threadId);
  }

  private static IssueAgentThread toModel(IssueAgentThreadDO row) {
    if (row == null) {
      return null;
    }
    return new IssueAgentThread(row.getIssueId(), row.getAgentName(), row.getThreadId());
  }
}
