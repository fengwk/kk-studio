package fun.fengwk.kkstudio.core.ai.runtime.model;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionResource;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Production {@link ModelExecutionResolver}.
 *
 * <p>Validation-only adapter: routes the frozen {@link ProviderRequest} through {@link
 * ProviderResolutionService} (which materialises the SDK-bound provider and reads the persisted
 * timeout policy) and freezes the resulting {@link ProviderResolutionService.ResolvedExecution}
 * into a {@link ProviderCallExecutor} closure. That closure is what gets returned as the {@link
 * ModelExecutionResource#executor()}, so each dispatch pays exactly one {@code
 * AgentProviderRevisionRepository.getByProviderNameAndVersion} and exactly one SDK {@code
 * ProviderFactory.create} call; {@link ProviderCallExecutor#execute} never touches the database
 * again.
 *
 * <p>Any failure inside {@link ProviderResolutionService#resolve} surfaces as an {@link
 * IllegalArgumentException} so the durable Model worker fails the claim with a clear setup failure.
 * The original {@link ProviderRequest} is never modified or rebuilt.
 */
@Component
public class DatabaseModelExecutionResolver implements ModelExecutionResolver {

  private final ProviderResolutionService providerResolution;
  private final ExecutorService modelExecutionExecutor;
  private final Clock clock;

  public DatabaseModelExecutionResolver(
      ProviderResolutionService providerResolution,
      @Qualifier("modelExecutionExecutor") ExecutorService modelExecutionExecutor,
      Clock clock) {
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
    this.modelExecutionExecutor =
        Objects.requireNonNull(modelExecutionExecutor, "modelExecutionExecutor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ModelExecutionResource resolve(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    ProviderResolutionService.ResolvedExecution resolved = providerResolution.resolve(request);
    ProviderCallExecutor callExecutor =
        new ProviderCallExecutor(resolved, modelExecutionExecutor, clock);
    return new ModelExecutionResource(callExecutor, resolved.timeoutPolicy());
  }
}
