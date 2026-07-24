/**
 * PostgreSQL final-schema {@code ThreadReconcileTransactions} adapter and its narrow MyBatis
 * boundary.
 *
 * <p>Every mutation locks {@code harness_thread} first, then reads or changes
 * Invocation/Input/Entry facts under execution-epoch and processor-token fencing. The adapter
 * decodes only frozen Entry and Input payloads; it never consults live Agent/Model definitions.
 * Redis activation remains outside these transactions.
 */
package fun.fengwk.kkstudio.core.harness.thread.reconcile;
