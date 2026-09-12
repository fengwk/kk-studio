package fun.fengwk.kkstudio.harness.environment.capability;

import java.time.Duration;
import java.util.Objects;

/**
 * 发给本地或远端 Environment Capability 的单次执行请求。
 *
 * <p>请求不携带 workdir：目录只来自具体工具 arguments，Daemon 的 coding 实现可以在一次调用内形成已校验的本地 {@code Path}，
 * 但不把它提升为通用执行状态。
 */
public record EnvironmentCapabilityExecutionRequest(
    EnvironmentCapabilityDescriptor descriptor, EnvironmentCapabilityCall call, Duration timeout) {

  public EnvironmentCapabilityExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call").validateFor(descriptor);
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
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
