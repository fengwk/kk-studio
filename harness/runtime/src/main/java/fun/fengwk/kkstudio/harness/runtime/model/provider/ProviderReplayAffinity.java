package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/** Provider terminal replay 的亲和性元数据。 */
public record ProviderReplayAffinity(
    ProviderType providerType, String providerName, UUID connectionGenerationId, String modelName) {

  public ProviderReplayAffinity {
    providerType = Objects.requireNonNull(providerType, "providerType");
    if (providerName == null || providerName.isBlank()) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    if (!providerName.equals(providerName.trim())) {
      throw new IllegalArgumentException("providerName must not contain surrounding whitespace");
    }
    connectionGenerationId =
        Objects.requireNonNull(connectionGenerationId, "connectionGenerationId");
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    if (!modelName.equals(modelName.trim())) {
      throw new IllegalArgumentException("modelName must not contain surrounding whitespace");
    }
  }

  @Override
  public String toString() {
    return "ProviderReplayAffinity[providerType="
        + providerType
        + ", providerName="
        + providerName
        + ", connectionGenerationId="
        + connectionGenerationId
        + ", modelName="
        + modelName
        + "]";
  }
}
