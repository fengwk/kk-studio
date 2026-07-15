package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Optional;

/** Resolves the immutable revision associated with a newly created child working copy policy. */
public interface WorkingCopyRevisionResolver {
  Optional<String> resolve(WorkingCopyPolicy policy, long childSessionId);
}
