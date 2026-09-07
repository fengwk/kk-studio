package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/** Provider 连接的非敏感描述；凭据由上层安全存储并在创建 adapter 时注入。 */
public record ProviderDescriptor(
    String providerName,
    ProviderType type,
    String endpoint,
    ModelCallTimeoutPolicy modelCallTimeoutPolicy,
    UUID connectionGenerationId) {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  public ProviderDescriptor {
    if (providerName == null || providerName.isBlank()) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    if (!providerName.equals(providerName.trim())) {
      throw new IllegalArgumentException("providerName must not contain surrounding whitespace");
    }
    type = Objects.requireNonNull(type, "type");
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank");
    }
    modelCallTimeoutPolicy =
        Objects.requireNonNull(modelCallTimeoutPolicy, "modelCallTimeoutPolicy");
    connectionGenerationId =
        Objects.requireNonNull(connectionGenerationId, "connectionGenerationId");
  }

  public ProviderDescriptor(
      String providerName,
      ProviderType type,
      String endpoint,
      ModelCallTimeoutPolicy modelCallTimeoutPolicy) {
    this(providerName, type, endpoint, modelCallTimeoutPolicy, ZERO_UUID);
  }

  public ProviderReplayAffinity affinity(String modelName) {
    return new ProviderReplayAffinity(type, providerName, connectionGenerationId, modelName);
  }
}
