/**
 * PostgreSQL durable execution-target activation queue.
 *
 * <p>Owns the {@code harness_execution_target} table, its trigger-driven NOTIFY wake channel, and
 * the dispatcher / listener pair that drains due rows on a single process-local thread.
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetRow} — immutable row
 *       projection handed to handlers.
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore} — narrow port:
 *       {@code schedule}, {@code park}, {@code lock}, {@code lockDue}, {@code rescheduleLocked},
 *       {@code deleteLocked}, {@code deleteIfExists}, {@code activateOldestEnvironment}, plus the
 *       dispatcher's lock-free reads.
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetHandler} — synchronous
 *       per-row handler invoked outside any transaction. Returning {@code false} reports a stale
 *       snapshot.
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetRouteEligibility} —
 *       read-only snapshot of locally READY route keys.
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.PostgresqlExecutionTargetDispatcher} —
 *       coalesced-wake drainer; one drain executor, one nearest-due timer; never invokes domain
 *       transactions on its own thread.
 *   <li>{@link fun.fengwk.kkstudio.core.harness.execution.PostgresqlExecutionTargetListener} —
 *       single-process {@code LISTEN harness_execution_target} loop using a long-lived blocking
 *       {@link org.postgresql.PGConnection#getNotifications(int)}.
 * </ul>
 *
 * <p>The schema stores one row per durable target. {@code dispatch_enabled} is the explicit
 * dispatch gate: due scans and nearest-due timing ignore parked rows, while {@code lock} and {@code
 * findAll} expose them for ownership and inspection. ENVIRONMENT Tool targets are parked at
 * materialization and activated FIFO by route using Tool creation order; a RUNNING, RETRY_WAIT, or
 * future WAITING_INTERACTION head blocks later siblings.
 *
 * <p>Schema triggers notify only enabled inserts, enable transitions, and strictly-earlier moves of
 * enabled rows. No application-level notifier or Redis queue state is involved in this substrate.
 */
package fun.fengwk.kkstudio.core.harness.execution;
