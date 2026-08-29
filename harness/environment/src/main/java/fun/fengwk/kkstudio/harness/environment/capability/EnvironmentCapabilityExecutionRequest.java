package fun.fengwk.kkstudio.harness.environment.capability;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 发给本地或远端 Environment Capability 的单次执行请求。 */
public record EnvironmentCapabilityExecutionRequest(
    EnvironmentCapabilityDescriptor descriptor,
    EnvironmentCapabilityCall call,
    Duration timeout,
    Path workdir) {

  public EnvironmentCapabilityExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call").validateFor(descriptor);
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    if (workdir != null && !workdir.isAbsolute()) {
      throw new IllegalArgumentException("workdir must be absolute when specified: " + workdir);
    }
  }

  /** 返回本次调用的有效超时：请求超时为 0 时使用 descriptor 默认超时；超过 descriptor 超时时截断。 */
  public Duration effectiveTimeout() {
    if (descriptor.timeout().isZero()) {
      return timeout;
    }
    if (timeout.isZero()) {
      return descriptor.timeout();
    }
    return timeout.compareTo(descriptor.timeout()) > 0 ? descriptor.timeout() : timeout;
  }
}
