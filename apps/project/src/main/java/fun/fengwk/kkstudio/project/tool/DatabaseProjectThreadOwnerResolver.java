package fun.fengwk.kkstudio.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;

import java.util.Objects;
import java.util.UUID;

/**
 * 生产 {@link ProjectThreadOwnerResolver}：直接以稳定绑定行与其 Harness Session 归属判定。
 *
 * <p>Thread 不存在或 Session 没有绑定时返回 false；其他读取异常原样传播。
 */
public class DatabaseProjectThreadOwnerResolver implements ProjectThreadOwnerResolver {

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueThreadExecution threadExecution;

  public DatabaseProjectThreadOwnerResolver(
      IssueAgentThreadRepository issueAgentThreadRepository, IssueThreadExecution threadExecution) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.threadExecution = Objects.requireNonNull(threadExecution, "threadExecution");
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isIssueAgentBranch(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return threadExecution
        .findThreadSessionId(threadId)
        .map(issueAgentThreadRepository::existsBySessionId)
        .orElse(false);
  }
}
