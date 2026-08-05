/**
 * Production adapters that bind the Runtime Model ports to PostgreSQL provider resources and an
 * injected {@link java.util.concurrent.ExecutorService}.
 *
 * <p>{@link fun.fengwk.kkstudio.core.ai.runtime.model.CoreModelGateway} is the production adapter
 * for {@link fun.fengwk.kkstudio.harness.runtime.port.ModelGateway}: it resolves the frozen {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest} through {@link
 * fun.fengwk.kkstudio.core.ai.runtime.model.ProviderResolutionService}, opens one {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider} with its configured timeout
 * policy and bridges the SDK stream onto the Runtime listener with terminal-once semantics. The
 * gateway never reads or writes the HarnessStore and delivers no listener callback before {@code
 * start} returns.
 *
 * <p>{@link fun.fengwk.kkstudio.core.ai.runtime.model.ModelExecutionConfiguration} owns the shared
 * virtual-thread executor and the {@link
 * fun.fengwk.kkstudio.core.ai.runtime.model.ModelGatewayConfig} lifecycle; Provider I/O for both
 * adapters runs on that executor.
 */
package fun.fengwk.kkstudio.core.ai.runtime.model;
