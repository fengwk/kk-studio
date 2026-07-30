/**
 * Production adapter binding the Runtime {@code ModelInvocationTransactions} port to the PostgreSQL
 * final schema ({@code harness_model_invocation} / {@code harness_thread}).
 *
 * <p>All mutations follow the strict lock order fixed by {@link
 * fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions}: {@code Thread FOR
 * UPDATE} must be acquired before {@code ModelInvocation FOR UPDATE}, except for {@code claim}
 * which first does a non-locking peek of {@code threadId} before re-locking. Every claim-following
 * update carries the full CAS predicate ({@code id}, {@code thread_id}, {@code status='RUNNING'},
 * {@code execution_epoch}, {@code attempt}, {@code worker_token}, {@code worker_until > now}) in
 * the SQL itself so the database is the final authority on ownership.
 *
 * <p>{@link HarnessModelWorkerConfiguration} composes the durable {@code ModelWorker} 及其 dedicated
 * scheduler。{@link HarnessModelInvocationThreadMapper} is a narrow mapper covering only the Thread
 * columns this adapter needs.
 */
package fun.fengwk.kkstudio.core.ai.runtime.model.worker;
