package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;

/**
 * Typed business conflict of the synchronous Harness control plane.
 *
 * <p>Thrown instead of a raw {@link IllegalStateException} for every user-recoverable rejection;
 * broken persistence invariants (wrong ownership, mixed sibling attachment, non-contiguous
 * ordinals, count mismatches) stay {@link IllegalStateException}.
 */
public final class HarnessRuntimeConflictException extends RuntimeException {

  private final Reason reason;

  public HarnessRuntimeConflictException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** The typed conflict category, stable for HTTP mapping and client retry decisions. */
  public Reason reason() {
    return reason;
  }

  /** Closed set of conflict categories of the Harness control plane. */
  public enum Reason {
    /** A revision-guarded control request does not match the current Thread revision. */
    STALE_REVISION,
    /** New command batch expected head/next-command-sequence cursor is stale. */
    STALE_COMMAND_CURSOR,
    /** Ordered replay: an existing clientCommandId carries a different payload. */
    COMMAND_ID_REUSED,
    /**
     * Ordered replay: only a strict subset of the batch ids exists; missing commands are never
     * filled.
     */
    PARTIAL_COMMAND_REPLAY,
    /**
     * Ordered replay: ids exist with equal payloads but sequences are not contiguous in request
     * order.
     */
    COMMAND_REPLAY_ORDER_MISMATCH,
    /** Thread has live work, queued input commands or a THREAD Work row and must quiesce first. */
    THREAD_NOT_QUIESCENT,
    /** Thread has a terminal Model/Tool result waiting to be applied atomically. */
    TERMINAL_APPLY_PENDING,
    /** MOVE_HEAD target lives in a different Session than the current head. */
    MOVE_TARGET_CROSS_SESSION,
    /**
     * MOVE_HEAD target is a continueModel=true TURN_END that would reactivate an old obligation.
     */
    MOVE_TARGET_HAS_CONTINUATION_OBLIGATION,
    /** Stop idempotency key was already used by a non-Stop close operation. */
    STOP_REQUEST_ID_REUSED,
    /** Approval target is missing, not owned by the request thread or not currently applicable. */
    APPROVAL_NOT_APPLICABLE,
    /** Approval is already decided and the request does not replay the stored decision. */
    APPROVAL_DECISION_MISMATCH
  }
}
