/**
 * PostgreSQL final-schema {@code ThreadReconcileTransactions} adapter and its narrow MyBatis
 * boundary.
 *
 * <p>Every mutation locks {@code harness_thread} first, then reads or changes
 * Invocation/Input/Entry facts under execution-epoch and processor-token fencing. The adapter
 * decodes only frozen Entry and Input payloads; it never consults live Agent/Model definitions.
 * Durable target mutation and PostgreSQL notification remain part of the same transaction boundary.
 */
package fun.fengwk.kkstudio.core.harness.thread.reconcile;
