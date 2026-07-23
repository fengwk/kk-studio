package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Claim and observation port for Platform invocation leases. */
public interface ToolInvocationWorkerStore {
  Optional<ClaimedToolInvocation> claimDue(String leaseOwner, Instant now, Duration leaseDuration);

  Optional<ClaimedToolInvocation> claimDueForThread(
      String leaseOwner, long threadId, Instant now, Duration leaseDuration);

  boolean heartbeat(ClaimedToolInvocation claimed, Instant now, Duration leaseDuration);

  Optional<ToolInvocation> find(long invocationId);
}
