package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The runtime-facing description of a model.
 *
 * <p>{@code providerResourceId} is the PostgreSQL durable resource id and stable credential
 * reference for the upstream provider; it is not a secret value and must never carry an API key.
 * {@code modelResourceId} is the PostgreSQL durable resource id for the model row, used for ledger
 * aggregation. {@code providerType} / {@code modelId} identify the upstream model that the adapter
 * dispatches to. Functional capabilities are expressed directly as the primitive {@code tools} /
 * {@code reasoning} booleans alongside {@link ModelInputModality}, removing the previous redundant
 * derived-capability tracking.
 */
public record ModelDescriptor(
    long providerResourceId,
    long modelResourceId,
    ProviderType providerType,
    String modelId,
    String displayName,
    long contextWindow,
    long maxOutputTokens,
    Set<ModelInputModality> inputModalities,
    boolean tools,
    boolean reasoning,
    List<ModelVariant> variants,
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
    displayName = requireNonBlank(displayName, "displayName");
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    if (maxOutputTokens <= 0 || maxOutputTokens > contextWindow) {
      throw new IllegalArgumentException("maxOutputTokens must be in (0, contextWindow]");
    }
    inputModalities = Set.copyOf(Objects.requireNonNull(inputModalities, "inputModalities"));
    if (inputModalities.isEmpty()) {
      throw new IllegalArgumentException("inputModalities must not be empty");
    }
    variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
    if (variants.stream()
        .anyMatch(
            variant ->
                variant.maxOutputTokens() != null && variant.maxOutputTokens() > maxOutputTokens)) {
      throw new IllegalArgumentException("variant maxOutputTokens must not exceed model maximum");
    }
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
