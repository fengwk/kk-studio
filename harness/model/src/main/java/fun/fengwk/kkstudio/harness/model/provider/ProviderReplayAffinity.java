package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/**
 * Provider terminal replay 的亲和性元数据。
 *
 * <p>{@code modelId} 是发往上游的真实 wire 模型标识；连接世代不影响回放资格。
 */
public record ProviderReplayAffinity(
    ProviderType providerType, String providerName, String modelId) {

  public ProviderReplayAffinity {
    providerType = Objects.requireNonNull(providerType, "providerType");
    if (providerName == null || providerName.isBlank()) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    if (!providerName.equals(providerName.trim())) {
      throw new IllegalArgumentException("providerName must not contain surrounding whitespace");
    }
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
