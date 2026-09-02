package fun.fengwk.kkstudio.platform.environment.registry;

import java.util.Objects;
import java.util.UUID;

/** {@link EnvironmentRegistry#tryAcquire} 的类型化绑定结果。 */
public sealed interface BindResult permits BindResult.Acquired, BindResult.RetryLater {

  record Acquired(UUID leaseToken) implements BindResult {
    public Acquired {
      Objects.requireNonNull(leaseToken, "leaseToken");
    }
  }

  record RetryLater(String message) implements BindResult {
    public RetryLater {
      Objects.requireNonNull(message, "message");
    }
  }

  static BindResult acquired(UUID leaseToken) {
    return new Acquired(leaseToken);
  }

  static BindResult retryLater(String message) {
    return new RetryLater(message);
  }
}
