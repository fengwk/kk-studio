package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;

/**
 * The runtime-facing description of a model selected for the current turn.
 *
 * <p>{@code providerName} and {@code modelName} are the catalog names used to resolve the current
 * provider and model definition. They are deliberately strings rather than database resource IDs or
 * an ambiguous upstream {@code modelId}. Functional capabilities are expressed directly as the
 * primitive {@code tools} / {@code reasoning} booleans. The effective variant chosen for the turn
 * lives beside this descriptor in the ephemeral execution value and the durable invocation request.
 */
public record ModelDescriptor(
    String providerName,
    long providerVersion,
    String modelName,
    ProviderType providerType,
    boolean tools,
    boolean reasoning,
    ModelPricing pricing,
    PromptCachePolicy promptCachePolicy) {

  public ModelDescriptor {
    providerName = requireName(providerName, "providerName");
    if (providerVersion < 0) {
      throw new IllegalArgumentException("providerVersion must not be negative");
    }
    modelName = requireName(modelName, "modelName");
    providerType = Objects.requireNonNull(providerType, "providerType");
    pricing = Objects.requireNonNull(pricing, "pricing");
    promptCachePolicy = Objects.requireNonNull(promptCachePolicy, "promptCachePolicy");
  }

  private static String requireName(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
    }
    if (name.equals("providerName") && value.indexOf('/') >= 0) {
      throw new IllegalArgumentException(name + " must not contain '/'");
    }
    return value;
  }
}
