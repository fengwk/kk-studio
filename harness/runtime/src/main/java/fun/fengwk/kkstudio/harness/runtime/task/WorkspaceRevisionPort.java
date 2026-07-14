package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Optional;

/**
 * Narrow T10 workspace preparation/projection boundary. Implementations may prepare a FORK or
 * EXCLUSIVE workspace and return its immutable revision; an empty result never implies a revision.
 */
public interface WorkspaceRevisionPort {
  Optional<String> prepare(long workspaceId, WorkspacePolicy policy, long childSessionId);
}
