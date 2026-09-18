package fun.fengwk.kkstudio.platform.project.tool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Supplies the exact internal Project tools available to a Harness thread role. */
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
}
