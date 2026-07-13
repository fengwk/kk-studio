package fun.fengwk.kkstudio.harness.model.provider;

import java.time.Duration;
import java.util.Objects;

/** Provider 连接的非敏感描述；凭据由上层安全存储并在创建 adapter 时注入。 */
public record ProviderDescriptor(
    String providerId, ProviderType type, String endpoint, Duration timeout) {

  public ProviderDescriptor {
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalArgumentException("providerId must not be blank");
    }
    type = Objects.requireNonNull(type, "type");
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank");
    }
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
