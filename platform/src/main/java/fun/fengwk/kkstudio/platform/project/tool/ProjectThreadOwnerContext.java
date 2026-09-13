package fun.fengwk.kkstudio.platform.project.tool;

import java.util.Objects;
import java.util.UUID;

/** Stable Project ownership coordinates resolved from a Harness thread. */
public record ProjectThreadOwnerContext(
    ProjectRole role, UUID projectId, UUID issueId, UUID runId, String agentName) {

  public ProjectThreadOwnerContext {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(projectId, "projectId");
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
    if (role == ProjectRole.COORDINATOR) {
      if (issueId != null || runId != null) {
        throw new IllegalArgumentException(
            "coordinator ownership must not contain issue run fields");
      }
    } else if (issueId == null || runId == null) {
      throw new IllegalArgumentException("issue run ownership requires issue and run");
    }
  }
}
