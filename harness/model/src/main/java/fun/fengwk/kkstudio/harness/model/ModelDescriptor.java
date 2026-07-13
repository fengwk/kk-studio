package fun.fengwk.kkstudio.harness.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 模型的稳定描述，不包含 Provider 凭据或单次请求参数。 */
public record ModelDescriptor(
    String providerId,
    String modelId,
    String displayName,
    long contextWindow,
    long maxOutputTokens,
    Set<ModelInputModality> inputModalities,
    Set<ModelCapability> capabilities,
    List<ModelVariant> variants,
    ModelPricing pricing) {

  public ModelDescriptor {
    providerId = requireNonBlank(providerId, "providerId");
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
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
    if (variants.stream()
        .anyMatch(
            variant ->
                variant.maxOutputTokens() != null && variant.maxOutputTokens() > maxOutputTokens)) {
      throw new IllegalArgumentException("variant maxOutputTokens must not exceed model maximum");
    }
    pricing = Objects.requireNonNull(pricing, "pricing");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
