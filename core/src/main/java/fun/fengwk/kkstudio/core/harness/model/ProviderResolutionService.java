package fun.fengwk.kkstudio.core.harness.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;

import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves a frozen {@link ProviderRequest} to a ready-to-call {@link ModelProvider} plus its
 * transport {@link ModelCallTimeoutPolicy}.
 *
 * <p>Implementations are expected to look up the persisted Provider row by {@code
 * request.model().providerResourceId()}, validate that the persisted type matches the frozen {@code
 * ProviderType} on the request, confirm a Harness {@code ProviderFactory} is registered for that
 * type and freeze a short-lived Provider adapter resource. The adapter creates the SDK-bound {@link
 * ModelProvider} only on the Provider I/O executor, after the durable deadline has capped the
 * transport timeout.
 */
public interface ProviderResolutionService {

  ResolvedExecution resolve(ProviderRequest request);

  /**
   * Frozen resolution result containing only a private Provider opener and non-secret timeout
   * policy.
   */
  final class ResolvedExecution {

    private final ModelCallTimeoutPolicy timeoutPolicy;
    private final Function<ModelCallTimeoutPolicy, ModelProvider> providerOpener;

    ResolvedExecution(
        ModelCallTimeoutPolicy timeoutPolicy,
        Function<ModelCallTimeoutPolicy, ModelProvider> providerOpener) {
      this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
      this.providerOpener = Objects.requireNonNull(providerOpener, "providerOpener");
    }

    public ModelCallTimeoutPolicy timeoutPolicy() {
      return timeoutPolicy;
    }

    ModelProvider openProvider(ModelCallTimeoutPolicy effectiveTimeoutPolicy) {
      return Objects.requireNonNull(
          providerOpener.apply(
              Objects.requireNonNull(effectiveTimeoutPolicy, "effectiveTimeoutPolicy")),
          "providerOpener returned null");
    }
  }
}
