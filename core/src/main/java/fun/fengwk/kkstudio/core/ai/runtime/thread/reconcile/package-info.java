/**
 * PostgreSQL final-schema {@code ThreadReconcileTransactions} adapter and its narrow MyBatis
 * boundary.
 *
 * <p>Every mutation locks {@code harness_thread} first, then reads or changes
 * Invocation/Input/Entry facts under execution-epoch and processor-token fencing. State transitions
 * decode frozen Entry and Input payloads; Model creation resolves the current catalog in one
 * repeatable-read snapshot and freezes the resulting request. Durable activation mutation and
 * PostgreSQL notification remain part of the same transaction boundary.
 */
package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;
