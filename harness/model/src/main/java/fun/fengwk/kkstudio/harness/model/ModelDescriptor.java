package fun.fengwk.kkstudio.harness.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 模型的稳定描述，不包含 Provider 凭据或单次请求参数。 */
public record ModelDescriptor(
    String providerId,
    String modelId,
    String displayName,
    Set<ModelCapability> capabilities,
    List<ModelVariant> variants,
    ModelPricing pricing) {

  public ModelDescriptor {
    providerId = requireNonBlank(providerId, "providerId");
    modelId = requireNonBlank(modelId, "modelId");
    displayName = requireNonBlank(displayName, "displayName");
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
    pricing = Objects.requireNonNull(pricing, "pricing");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
