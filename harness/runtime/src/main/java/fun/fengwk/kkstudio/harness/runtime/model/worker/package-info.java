/**
 * Terminal-driven durable ModelInvocation execution.
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker} claims one Invocation
 * through {@link fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions},
 * resolves the execution resource, and hands Provider I/O to its package-local {@code
 * ModelExecutionCallback}. That callback owns listener lifecycle and watchdogs; {@code
 * ModelStreamAccumulator} reconciles stream deltas; {@code ModelTerminalCompleter} owns terminal
 * and retry mutations. Terminal state is committed only through the transaction port; the port
 * atomically marks the owning Thread runnable and schedules its durable target.
 *
 * <p>Delta is a bounded realtime projection only. Worker lease heartbeat and real Provider activity
 * are deliberately separate: only Provider delta is eligible to advance {@code lastActivityAt}. A
 * reclaimed RUNNING lease is marked UNKNOWN rather than replaying an unconfirmed Provider call.
 * This package depends only on runtime domain types, Model API, Runtime ports/retry contracts,
 * java.base/Jackson and the SLF4J API; it never depends on Spring, JDBC, Redis, HTTP or a Provider
 * SDK.
 */
package fun.fengwk.kkstudio.harness.runtime.model.worker;
