/**
 * Runtime Model boundary: stateless Model/Provider contracts plus the durable {@code
 * ModelInvocation} aggregate.
 *
 * <p>The package is the single home for Runtime-side Model contracts and Model invocation
 * orchestration. It holds both the value-object/Provider contracts and the durable aggregate stored
 * in the {@code harness_model_invocation} PostgreSQL table; sub-packages split responsibilities
 * along codec, Provider protocol, cache policy, planning and worker transaction lines.
 *
 * <p>Public surface and responsibility boundary:
 *
 * <ul>
 *   <li>Top-level value objects: {@link fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor},
 *       {@link fun.fengwk.kkstudio.harness.runtime.model.ModelVariant}, {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelCost}, {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelPricing}, {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelUsage} and {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality} describe a model, its
 *       modalities, its usage/cost accounting and its variants without depending on Tool, Session,
 *       Runtime orchestration, Spring or persistence.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.cache}: prompt-cache policy, capability,
 *       mode, retention and provider control value objects that travel with the request.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.provider}: Provider-facing request,
 *       response, message, content-block, stream and exception contracts that all SDK adapters must
 *       speak. Specific Provider SDK adapter implementations live in the {@code core} module under
 *       {@code fun.fengwk.kkstudio.core.ai.runtime.model.provider} and must never leak SDK types
 *       back into this boundary.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.codec} and {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.provider.codec}: strict deterministic JSON codecs
 *       used at the Model/Provider wire boundary. {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec} is the single
 *       authoritative codec for {@code ModelDescriptor}/{@code ModelVariant}; the provider-side
 *       codecs must delegate to it instead of re-implementing the same fields.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation}: the immutable Runtime
 *       aggregate mirroring the {@code harness_model_invocation} row. It owns the original {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest}, the shared runtime
 *       {@link fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus} lifecycle, attempt
 *       counters, timing fields, the shared runtime {@link
 *       fun.fengwk.kkstudio.harness.runtime.execution.Lease}, the terminal result/error payload and
 *       the applied timestamp. State transitions live in the separate {@code
 *       ModelInvocationTransactions} component and are out of scope here.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} carries the minimum
 *       terminal error snapshot (kind + non-blank message) so Runtime callers can render
 *       transient/permanent classifications without re-reading the original exception.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec} is the
 *       strict, deterministic JSON boundary for {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} used by the persistence
 *       adapter.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.plan} and {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.worker}: planner and worker transaction
 *       components that act on {@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation}.
 * </ul>
 *
 * <p>Dependency direction: this package depends on runtime shared lifecycle types ({@link
 * fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus}, {@link
 * fun.fengwk.kkstudio.harness.runtime.execution.Lease}) and on Jackson {@code JsonNode}; lifecycle
 * status and lease records are not redefined here. Provider SDK adapters stay in {@code core};
 * Spring, JDBC, Redis, HTTP and SDK types must not leak into this package or any of its
 * sub-packages.
 */
package fun.fengwk.kkstudio.harness.runtime.model;
