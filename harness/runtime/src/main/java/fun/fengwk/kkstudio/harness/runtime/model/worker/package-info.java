/**
 * Terminal-driven durable ModelInvocation execution.
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker} claims one Invocation
 * through {@link fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions},
 * delegates external I/O to {@link fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutor},
 * and accepts Provider callbacks without writing Entry/head/Usage. Terminal state is committed only
 * through the transaction port; the port atomically marks the owning Thread runnable, after which
 * the Worker emits a best-effort activation hint.
 *
 * <p>Delta is a bounded realtime projection only. Worker lease heartbeat and real Provider activity
 * are deliberately separate: only Provider delta is eligible to advance {@code lastActivityAt}. A
 * reclaimed RUNNING lease is marked UNKNOWN rather than replaying an unconfirmed Provider call.
 * This package depends only on Kernel, Model API, Runtime ports/retry contracts and
 * java.base/Jackson, never Spring, JDBC, Redis, HTTP or a Provider SDK.
 */
package fun.fengwk.kkstudio.harness.runtime.model.worker;
