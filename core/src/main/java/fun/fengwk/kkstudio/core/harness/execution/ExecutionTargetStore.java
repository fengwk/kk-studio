package fun.fengwk.kkstudio.core.harness.execution;

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
 * without a lock.
 *
 * <p>The schema-level trigger fires {@code pg_notify('harness_execution_target')} on every insert
 * and on every strictly-earlier update of {@code available_at}; no application-level notifier is
 * involved.
 */
public interface ExecutionTargetStore {

  /**
   * Schedule a target or earlier-reschedule an existing one. If the row does not exist, it is
   * inserted with {@code availableAt}. If the row exists and the requested {@code availableAt} is
   * strictly earlier than the current value, the row is updated to the earlier time. If the current
   * value is already at or before the requested time, no change is made.
   *
   * <p>Returns the number of affected rows: 1 when a row was inserted or moved earlier; 0 when the
   * existing earlier value was preserved.
   */
  int schedule(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

  /**
   * Acquire a row-level lock on {@code (kind, id)} regardless of its due time. Used by an existing
   * owner to renew or release its watchdog target.
   */
  Optional<ExecutionTargetRow> lock(ExecutionTargetKind kind, long id);

  /**
   * Acquire a row-level lock on {@code (kind, id)} and return the row iff it currently exists and
   * its {@code availableAt} is at or before {@code now}. The caller MUST advance the row in the
   * same transaction via {@link #rescheduleLocked(ExecutionTargetKind, long, String, Instant)} or
   * {@link #deleteLocked(ExecutionTargetKind, long)}; otherwise the durable state is unchanged.
   */
  Optional<ExecutionTargetRow> lockDue(ExecutionTargetKind kind, long id, Instant now);

  /**
   * Overwrite an already-locked row's route key and available time. Returns 1 on success; 0 when
   * the lock has been lost (should not happen for a caller that holds a lock from {@link
   * #lockDue}). Lease extensions ({@code availableAt} later than the current value) do not fire
   * NOTIFY.
   */
  int rescheduleLocked(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt);

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
   * Activate the oldest eligible ENVIRONMENT tool target for {@code routeKey} by moving its {@code
   * availableAt} earlier to {@code availableAt}, iff the current value is strictly later. The
   * selection uses {@code FOR UPDATE SKIP LOCKED} so concurrent callers converge without blocking.
   *
   * <p>Returns {@code true} when one row was moved earlier; {@code false} when no row matched (no
   * environment target, the oldest is already at or before the requested time, or another caller
   * holds the oldest row's lock).
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
