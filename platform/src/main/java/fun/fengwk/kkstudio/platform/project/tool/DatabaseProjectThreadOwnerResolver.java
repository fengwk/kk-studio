package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;

import java.util.Objects;
import java.util.UUID;

/**
 * 生产 {@link ProjectThreadOwnerResolver}：直接以稳定绑定行与其 Harness Session 归属判定。
 *
 * <p>判定是纯读取：Thread 不存在、Session 不存在或没有任何绑定都返回 false，绝不为了"看起来有 owner"而回退到别的归属。
 */
public class DatabaseProjectThreadOwnerResolver implements ProjectThreadOwnerResolver {

  private final IssueAgentThreadRepository issueAgentThreadRepository;

  public DatabaseProjectThreadOwnerResolver(IssueAgentThreadRepository issueAgentThreadRepository) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isIssueAgentBranch(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return issueAgentThreadRepository.isIssueAgentBranch(threadId);
  }
}
