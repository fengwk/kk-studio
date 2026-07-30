package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import java.util.Objects;

/**
 * Outcome of one permission transition evaluated against a freshly claimed Tool invocation.
 *
 * <p>Returned exclusively by {@link PermissionResolver} after every durable mutation the state
 * machine may produce has been persisted. The owning {@link ToolWorker} consumes this sealed type
 * to decide whether to proceed into {@link ExecutionCallback} ({@link Resolved}), park until
 * approval ({@link AwaitingApproval}), converge a permission denial to terminal {@code FAILED}
 * ({@link Denied}), or accept an already-terminal row ({@link TerminalFailed}).
 */
sealed interface PermissionResolution {

  /** Proceed: dispatch the {@link ExecutablePlan} on the durable route. */
  final class Resolved implements PermissionResolution {
    private final ExecutablePlan plan;

    Resolved(ExecutablePlan plan) {
      this.plan = Objects.requireNonNull(plan, "plan");
    }

    ExecutablePlan plan() {
      return plan;
    }
  }

  /** ASK has been atomically persisted with a parked target and an OPEN interaction. */
  final class AwaitingApproval implements PermissionResolution {}

  /** DENY has been atomically persisted as a terminal {@code FAILED + DENIED} row. */
  final class Denied implements PermissionResolution {}

  /**
   * A boundary failure or invariant violation has already been terminally persisted. The owning
   * worker must not run any external Tool I/O for this invocation.
   */
  final class TerminalFailed implements PermissionResolution {}
}
