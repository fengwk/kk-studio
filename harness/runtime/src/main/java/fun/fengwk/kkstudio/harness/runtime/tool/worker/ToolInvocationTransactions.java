package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ToolInvocation worker use-case transaction port driven by the single {@code
 * harness_execution_target} queue. Lock order is Thread -> ToolInvocation -> ExecutionTarget; every
 * mutation acquires the durable target row in the same transaction.
 *
 * <p>Terminal mutations atomically mark the owning Thread runnable and reschedule its durable
 * Thread target. Retry mutations keep the Thread suspended and only reschedule the ToolInvocation
 * target.
 */
public interface ToolInvocationTransactions {

  /**
   * Acquire ownership of the invocation for a worker token. The transaction revalidates the owning
   * Thread, the frozen ToolInvocation row, and the {@link
   * fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind#TOOL_INVOCATION} target
   * before flipping the row to {@link InvocationStatus#RUNNING} and rescheduling the target's
   * {@code available_at} to the worker lease deadline.
   *
   * <p>Returns {@link Optional#empty()} when the target row is absent or not yet due, or when a
   * claim precondition fails. A missing owning Thread is a persistence invariant breach and fails
   * the transaction.
   */
  Optional<ClaimedToolInvocation> claim(
      long invocationId, String workerToken, Duration workerLeaseDuration, Instant now);

  /** Extend an owned invocation's lease and reschedule its target to the new lease deadline. */
  ToolInvocationUpdateOutcome renew(
      ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now);

  /** Record a partial-result activity timestamp on an owned invocation. */
  ToolInvocationUpdateOutcome recordActivity(
      ClaimedToolInvocation claimed, Instant activityAt, Instant now);

  /**
   * Release an invocation that was claimed but never dispatched. The target's {@code available_at}
   * is rescheduled to {@code now} (for {@link InvocationStatus#QUEUED} releases) or {@code
   * nextAttemptAt} (for {@link InvocationStatus#RETRY_WAIT} releases). The pre-claim state comes
   * exclusively from {@link ClaimedToolInvocation#previousStatus()}; the owning Thread is not
   * touched.
   */
  ToolInvocationUpdateOutcome releaseUnstarted(
      ClaimedToolInvocation claimed, Instant nextAttemptAt, Instant now);

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

  /**
   * Schedule an idempotent retry for the owned invocation. The target's {@code available_at} is
   * rescheduled to {@code nextAttemptAt}; the owning Thread is left suspended.
   */
  ToolInvocationUpdateOutcome scheduleRetry(
      ClaimedToolInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now);
}
