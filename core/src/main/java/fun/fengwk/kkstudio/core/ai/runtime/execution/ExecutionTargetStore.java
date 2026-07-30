package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Narrow port over {@code harness_execution_target} used by both the dispatcher and domain
 * transactions.
 *
 * <p>Reads return lock-free snapshots so the dispatcher never holds a row lock while invoking
 * external Provider/Tool I/O. Each domain transaction that wants to claim a target acquires its own
 * row lock via {@link #lockDue(ExecutionTargetKind, long, Instant)} and advances the state with
 * {@link #rescheduleLocked(ExecutionTargetKind, long, String, Instant)} or {@link
 * #deleteLocked(ExecutionTargetKind, long)} in the same transaction. {@link
 * #deleteIfExists(ExecutionTargetKind, long)} is the conditional variant for callers that race
 * without a lock. {@link #parkLocked} and {@link #activateLocked} are strict, transaction-bound
 * variants required by the durable Tool permission state machine: ASK must atomically park the
 * target so the FIFO gate remains consistent, and approval must atomically re-enable it.
 *
 * <p>The schema-level trigger fires {@code pg_notify('harness_execution_target')} for enabled
 * inserts, enable transitions, and strictly-earlier updates of an enabled row. Parked/disabled rows
 * are durable but excluded from due scans and nearest-due timing; {@link #lock} and {@link
 * #findAll} still expose them. ENVIRONMENT Tool route heads are activated FIFO by their joined Tool
 * invocation's {@code created_at}, {@code assistant_entry_id}, {@code ordinal}, and {@code id}; an
 * active nonterminal head blocks later siblings.
 */
public interface ExecutionTargetStore {

  /**
   * Schedule a target or earlier-reschedule an existing one. If the row does not exist, it is
   * inserted enabled with {@code availableAt}. If the row exists disabled, it is enabled and its
   * route/time are replaced with the requested normal target values. If the row is already enabled,
   * the route and time are updated only when {@code availableAt} is strictly earlier than the
   * current value. If the current enabled value is already at or before the requested time, no
   * change is made.
   *
   * <p>Returns the number of affected rows: 1 when a row was inserted, enabled, or moved earlier; 0
   * only when an existing enabled value at or before the requested time was preserved.
   */
  int schedule(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Create one initially parked target. Parking inserts a disabled row and never updates an
   * existing row. It is reserved for newly materialized ENVIRONMENT {@code TOOL_INVOCATION}
   * targets; callers must activate the route head separately after the complete batch is present.
   *
   * <p>Inputs are validated like {@link #schedule}, with a non-blank route key and {@link
   * ExecutionTargetKind#TOOL_INVOCATION} required. Returns 1 when the row is newly inserted and 0
   * when the primary key already exists; a 0 result leaves the existing row untouched and must be
   * treated as a conflict by a caller that requires initial creation.
   */
  int park(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Acquire a row-level lock on {@code (kind, id)} regardless of its due time. Used by an existing
   * owner to renew or release its watchdog target.
   */
  Optional<ExecutionTargetRow> lock(ExecutionTargetKind kind, long id);

  /**
   * Acquire a row-level lock on {@code (kind, id)} and return the row iff it currently exists, is
   * dispatch-enabled, and its {@code availableAt} is at or before {@code now}. Disabled rows are
   * intentionally invisible to this gate. The caller MUST advance the row in the same transaction
   * via {@link #rescheduleLocked(ExecutionTargetKind, long, String, Instant)} or {@link
   * #deleteLocked(ExecutionTargetKind, long)}; otherwise the durable state is unchanged.
   */
  Optional<ExecutionTargetRow> lockDue(ExecutionTargetKind kind, long id, Instant now);

  /**
   * Overwrite an already-locked row's route key and available time while preserving its {@code
   * dispatch_enabled} state. Returns 1 on success; 0 when the lock has been lost (should not happen
   * for a caller that holds a lock from {@link #lockDue}). Lease extensions ({@code availableAt}
   * later than the current value) do not fire NOTIFY, and a disabled row remains disabled.
   */
  int rescheduleLocked(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Disable an already-locked target row while preserving its route key and available time. The
   * caller MUST already hold a row lock on the target acquired via {@link #lock} (or {@link
   * #lockDue}). Returns 1 when the row was flipped to disabled; 0 when the lock has been lost. A
   * row that is already disabled is left untouched and the call returns 1.
   */
  int parkLocked(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Enable an already-locked target row and overwrite its route key and available time. The caller
   * MUST already hold a row lock on the target. Returns 1 when the row was enabled and the route
   * /time were rewritten; 0 when the lock has been lost. An already-enabled row's NOTIFY trigger is
   * only fired when the new {@code availableAt} is strictly earlier than the current value.
   */
  int activateLocked(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Delete an already-locked row. Returns 1 on success; 0 when the lock has been lost. Use {@link
   * #deleteIfExists(ExecutionTargetKind, long)} when the caller does not hold a lock.
   */
  int deleteLocked(ExecutionTargetKind kind, long id);

  /**
   * Conditional delete without a row lock. Returns 1 when the row was deleted by this call; 0 when
   * the row was absent (already advanced by another node).
   */
  int deleteIfExists(ExecutionTargetKind kind, long id);

  /**
   * Activate the oldest nonterminal ENVIRONMENT {@code TOOL_INVOCATION} target for {@code routeKey}
   * according to the joined invocation's {@code created_at}, {@code assistant_entry_id}, {@code
   * ordinal}, and {@code id}. The queue-member statuses are {@code QUEUED}, {@code RUNNING}, {@code
   * RETRY_WAIT}, and {@code WAITING_INTERACTION}. The target row is selected with {@code FOR UPDATE
   * SKIP LOCKED}; a locked head does not allow a later sibling to pass.
   *
   * <p>Only a {@code QUEUED} head can be enabled or moved to {@code availableAt}. A RUNNING,
   * RETRY_WAIT, or WAITING_INTERACTION head leaves the route unchanged and blocks later siblings.
   * If an enabled head is already at or before the requested time, the operation is a normal no-op.
   * Returns {@code true} only when the head was enabled or moved earlier.
   */
  boolean activateOldestEnvironment(String routeKey, Instant availableAt);

  /**
   * Lock-free scan of due targets eligible for the given route snapshot. The returned projections
   * may be stale by the time the handler runs; domain transactions rely on their own {@code
   * lockDue} to fence the durable state.
   */
  List<ExecutionTargetRow> findEligibleDue(
      ExecutionTargetRouteEligibility routeEligibility, Instant now, int limit);

  /**
   * Minimum {@code availableAt} across all eligible targets (due or future). The dispatcher uses
   * this to arm its single nearest-due timer.
   */
  Optional<Instant> findNearestEligibleAvailableAt(
      ExecutionTargetRouteEligibility routeEligibility);

  /** Snapshot of every row; for tests and inspection only. */
  List<ExecutionTargetRow> findAll();
}
