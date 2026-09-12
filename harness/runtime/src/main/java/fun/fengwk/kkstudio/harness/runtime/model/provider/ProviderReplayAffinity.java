package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * Provider terminal replay 的亲和性元数据。
 *
 * <p>{@code modelId} 是发往上游的真实 wire 模型标识：native replay state 只对同一 wire 模型有效，逻辑模型名的变更不构成亲和性差异。
 */
public record ProviderReplayAffinity(
    ProviderType providerType, String providerName, UUID connectionGenerationId, String modelId) {

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
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException("modelId must not be blank");
    }
    if (!modelId.equals(modelId.trim())) {
      throw new IllegalArgumentException("modelId must not contain surrounding whitespace");
    }
  }

  @Override
  public String toString() {
    return "ProviderReplayAffinity[providerType=" + providerType + "]";
  }
}
