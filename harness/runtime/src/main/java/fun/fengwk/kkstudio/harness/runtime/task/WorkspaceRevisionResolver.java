package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Optional;

/** Resolves the immutable revision associated with a newly created child workspace policy. */
public interface WorkspaceRevisionResolver {
  Optional<String> resolve(long workspaceId, WorkspacePolicy policy, long childSessionId);
}
