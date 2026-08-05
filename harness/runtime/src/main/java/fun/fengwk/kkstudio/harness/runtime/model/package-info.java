/**
 * Runtime Model boundary: stateless Model/Provider contracts and typed invocation errors.
 *
 * <p>Top-level value objects describe a model and its accounting without depending on Tool,
 * Session, Runtime orchestration, Spring or persistence; sub-packages split codec, Provider
 * protocol and cache policy responsibilities.
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
 *       modalities, its usage/cost accounting and its variants.
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
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} carries the minimum
 *       terminal error snapshot (kind + non-blank message) so Runtime callers can render
 *       transient/permanent classifications without re-reading the original exception.
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec} is the
 *       strict, deterministic JSON boundary for {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} used by the persistence
 *       adapter.
 * </ul>
 *
 * <p>The durable {@code ModelInvocation} aggregate and its codecs live in {@code
 * harness.runtime.invocation}; state transitions are executed by {@code harness.runtime.processor}.
 * Dependency direction: this package depends only on Jackson {@code JsonNode}; Spring, JDBC, Redis,
 * HTTP and SDK types must not leak into this package or any of its sub-packages.
 */
package fun.fengwk.kkstudio.harness.runtime.model;
