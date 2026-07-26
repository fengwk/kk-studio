package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;

/**
 * The runtime-facing description of a model selected for the current turn.
 *
 * <p>{@code providerResourceId} is the PostgreSQL durable resource id and stable credential
 * reference for the upstream provider; it is not a secret value and must never carry an API key.
 * {@code modelResourceId} is the PostgreSQL durable resource id for the model row, used for ledger
 * aggregation. {@code providerType} / {@code modelId} identify the upstream model that the adapter
 * dispatches to. Functional capabilities are expressed directly as the primitive {@code tools} /
 * {@code reasoning} booleans. Definition-only display/capability metadata stays outside this
 * runtime value; the effective variant chosen for the turn lives on {@link ModelSnapshot}.
 */
public record ModelDescriptor(
    long providerResourceId,
    long modelResourceId,
    ProviderType providerType,
    String modelId,
    boolean tools,
    boolean reasoning,
    ModelPricing pricing,
    PromptCachePolicy promptCachePolicy) {

  public ModelDescriptor {
    if (providerResourceId <= 0) {
      throw new IllegalArgumentException("providerResourceId must be positive");
    }
    if (modelResourceId <= 0) {
      throw new IllegalArgumentException("modelResourceId must be positive");
    }
    providerType = Objects.requireNonNull(providerType, "providerType");
    modelId = requireNonBlank(modelId, "modelId");
    pricing = Objects.requireNonNull(pricing, "pricing");
    promptCachePolicy = Objects.requireNonNull(promptCachePolicy, "promptCachePolicy");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
