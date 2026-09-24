package fun.fengwk.kkstudio.platform.project.tool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Supplies the exact internal Issue tools available to a Harness thread role. */
public class ProjectRoleToolSelector {

  private final ProjectThreadOwnerResolver ownerResolver;

  public ProjectRoleToolSelector(ProjectThreadOwnerResolver ownerResolver) {
    this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
  }

  public List<String> select(UUID threadId) {
    return ownerResolver
        .resolve(threadId)
        .map(ProjectThreadOwnerContext::role)
        .map(ProjectRoleToolType::namesForRole)
        .orElseGet(List::of);
  }

  /** 该 thread 是否属于稳定的 Issue+Agent 归属：与是否有活动 Run 无关，用于关闭 Issue Agent Branch 的 Goal 工具面。 */
  public boolean isIssueAgentBranch(UUID threadId) {
    return ownerResolver.isIssueAgentBranch(threadId);
  }
}
