package fun.fengwk.kkstudio.platform.project.tool;

import java.util.Objects;
import java.util.UUID;

/** Product owner context resolved from an active IssueRun on a thread. */
public record ProjectThreadOwnerContext(
    ProjectRole role, UUID projectId, UUID issueId, UUID runId, String agentName) {

  public ProjectThreadOwnerContext {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(agentName, "agentName");
  }
}
