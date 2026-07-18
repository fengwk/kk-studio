package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Optional extension of tool claim stores that can claim due work for a single thread. */
public interface ThreadScopedToolClaimStore {
  Optional<ClaimedToolInvocation> claimDueForThread(
      String leaseOwner, long threadId, Instant now, Duration leaseDuration);
}
