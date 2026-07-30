package fun.fengwk.kkstudio.core.ai.runtime.execution;

/**
 * Short synchronous handler invoked by the dispatcher for each eligible due target snapshot. The
 * dispatcher does not lock the row or hold any transaction on the handler's behalf; the handler
 * receives a lock-free snapshot and re-locks any durable state it must advance in its own
 * transaction.
 *
 * <p>Returning {@code false} tells the dispatcher to skip this row — for example because the work
 * has already been claimed by another node and the durable state has advanced. The dispatcher will
 * continue draining the rest of the batch.
 */
@FunctionalInterface
public interface ExecutionTargetHandler {

  /**
   * Handle one due target.
   *
   * @param row lock-free snapshot of the due row the dispatcher selected.
   * @return {@code true} if the handler accepted the row and intends to advance the durable state;
   *     {@code false} if the row is stale and the dispatcher should skip it.
   */
  boolean handle(ExecutionTargetRow row);
}
