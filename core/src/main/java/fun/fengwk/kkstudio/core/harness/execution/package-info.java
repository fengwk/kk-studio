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
 *       {@code schedule}, {@code lock}, {@code lockDue}, {@code rescheduleLocked}, {@code
 *       deleteLocked}, {@code deleteIfExists}, {@code activateOldestEnvironment}, plus the
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
 * <p>No Redis or Redis Pub/Sub is involved. Domain integration of Thread / Model / Tool
 * transactions will compose {@link
 * fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore#lockDue} with {@link
 * fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore#rescheduleLocked} or {@link
 * fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore#deleteLocked} in their own
 * transactions to advance the durable state.
 */
package fun.fengwk.kkstudio.core.harness.execution;
