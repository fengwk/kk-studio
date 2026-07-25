/**
 * Durable ModelInvocation Runtime aggregate and its JSON error snapshot.
 *
 * <p>This package owns the in-memory representation of one logical Model invocation stored in the
 * {@code harness_model_invocation} PostgreSQL table. The aggregate feeds both the persistence
 * adapter and terminal-driven {@code ModelWorker}; it enforces lifecycle, time-order, lease,
 * payload and {@code appliedAt} invariants at the constructor boundary.
 *
 * <p>Responsibility boundary:
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation} is the immutable Runtime
 *       aggregate; it owns the original {@link
 *       fun.fengwk.kkstudio.harness.model.provider.ProviderRequest}, the shared runtime {@link
 *       fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus} lifecycle, attempt
 *       counters, timing fields, the shared runtime {@link
 *       fun.fengwk.kkstudio.harness.runtime.execution.Lease}, the terminal result/error payload and
 *       the applied timestamp. State transitions live in a separate {@code
 *       ModelInvocationTransactions} component and are out of scope here.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} carries the minimum
 *       terminal error snapshot (kind + non-blank message) so Runtime callers can render
 *       transient/permanent classifications without re-reading the original exception.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec} is the
 *       strict, deterministic JSON boundary for {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} used by the persistence
 *       adapter.
 * </ul>
 *
 * <p>Dependency direction: this package depends on runtime shared lifecycle types ({@link
 * fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus}, {@link
 * fun.fengwk.kkstudio.harness.runtime.execution.Lease}) and on in-module {@code harness.model}
 * value objects plus Jackson {@code JsonNode}; lifecycle status and lease records are not redefined
 * here. No Spring, JDBC, Redis, threading or SDK type may leak in.
 */
package fun.fengwk.kkstudio.harness.runtime.model;
