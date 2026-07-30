package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
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
   * <p>Returns {@link Optional#empty()} when the target row is absent or not yet due, when a claim
   * precondition fails, or when a RUNNING/PENDING lease has expired and was reset to QUEUED/PENDING
   * (the target is rescheduled to {@code now} and the caller must re-dispatch). A missing owning
   * Thread is a persistence invariant breach and fails the transaction.
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

  /**
   * Persist the final authorized execution plan and transition permission state to {@code ALLOWED}.
   * The Tool descriptor, arguments, location and environment name are overwritten atomically with
   * the caller-supplied final plan. The durable target row is preserved (or rescheduled to the
   * worker lease deadline) so the caller can immediately dispatch the post-allow invocation.
   */
  ToolInvocationUpdateOutcome persistPermissionAllowed(
      ClaimedToolInvocation claimed,
      ToolBinding finalBinding,
      String finalArgumentsJson,
      Instant now);

  /**
   * Atomically persist the final ASK plan, transition to {@code WAITING_INTERACTION} with {@code
   * permission_state = ASKED}, clear worker clocks, and insert exactly one OPEN Interaction owned
   * by this Tool. The execution target is parked (disabled, route/time preserved) in the same
   * transaction so the FIFO gate is preserved while the prompt is outstanding. {@code expiresAt} is
   * reserved for the next phase; this slice always passes {@code null}.
   */
  ToolInvocationUpdateOutcome awaitPermission(
      ClaimedToolInvocation claimed,
      ToolBinding finalBinding,
      String finalArgumentsJson,
      PermissionPromptPreview prompt,
      Instant now);

  /**
   * Atomically transition permission state to {@code DENIED} and the row to terminal {@code FAILED}
   * with error kind {@code PERMISSION_DENIED}. The owning Thread is marked runnable, the durable
   * Tool target is deleted, the owning Thread target is rescheduled, and the next environment head
   * (if any) is activated atomically. No external Tool I/O is permitted on this path.
   */
  ToolInvocationUpdateOutcome denyPermission(ClaimedToolInvocation claimed, Instant now);
}
