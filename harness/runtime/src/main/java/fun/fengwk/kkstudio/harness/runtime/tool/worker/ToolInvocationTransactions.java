package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ToolInvocation worker use-case transaction port. Terminal mutations atomically mark the owning
 * Thread runnable; retry mutations keep the Thread suspended and only reschedule the Invocation.
 */
public interface ToolInvocationTransactions {

  Optional<ToolInvocation> findClaimable(long invocationId, Instant now);

  Optional<ToolInvocation> findNextClaimable(
      ToolExecutionLocation location, String environmentName, Instant now);

  Optional<ToolInvocation> findNextExpiredRunning(ToolExecutionLocation location, Instant now);

  Optional<ClaimedToolInvocation> claim(
      long invocationId,
      String workerToken,
      Duration executionTimeout,
      Duration workerLeaseDuration,
      Instant now);

  ToolInvocationUpdateOutcome renew(
      ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now);

  ToolInvocationUpdateOutcome recordActivity(
      ClaimedToolInvocation claimed, Instant activityAt, Instant now);

  ToolInvocationUpdateOutcome releaseUnstarted(
      ClaimedToolInvocation claimed,
      InvocationStatus previousStatus,
      Instant nextAttemptAt,
      Instant now);

  ToolInvocationUpdateOutcome completeSuccess(
      ClaimedToolInvocation claimed,
      Supplier<ToolResult> resultSupplier,
      Instant lastObservedActivityAt,
      Instant now);

  ToolInvocationUpdateOutcome completeFailure(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now);

  ToolInvocationUpdateOutcome completeCancelled(
      ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now);

  ToolInvocationUpdateOutcome completeUnknown(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now);

  ToolInvocationUpdateOutcome scheduleRetry(
      ClaimedToolInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now);
}
