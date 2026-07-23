/**
 * Production adapter that binds the Runtime {@code ModelExecution} ports to PostgreSQL provider
 * resources and an injected {@link java.util.concurrent.ExecutorService}.
 *
 * <p>{@link fun.fengwk.kkstudio.core.harness.model.DatabaseModelExecutionResolver} is the only
 * implementation of {@link
 * fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionResolver}: it resolves a frozen
 * {@link fun.fengwk.kkstudio.harness.model.provider.ProviderRequest} to a private, short-lived
 * Provider adapter resource purely by {@code ProviderRequest.model().providerResourceId()} (an
 * {@link fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider} primary key). It must
 * never consult {@link fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository}, agent
 * definitions, threads or session stores.
 *
 * <p>The returned per-attempt executor delegates blocking Provider I/O to a virtual-thread {@link
 * java.util.concurrent.ExecutorService}, bridges the SDK's {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler} onto the Runtime listener, caps
 * the transport total timeout by the durable deadline and exposes a delayed-bind cancel handle.
 *
 * <p>{@link fun.fengwk.kkstudio.core.harness.model.ModelExecutionConfiguration} owns the shared
 * virtual-thread executor lifecycle.
 */
package fun.fengwk.kkstudio.core.harness.model;
