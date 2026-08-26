package fun.fengwk.kkstudio.harness.tool.capability;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 提交给 Environment Capability SPI 的单次执行请求。
 *
 * <p>构造时先对 {@code call} 做 schema 驱动的静默归一化再校验。{@code workdir} 的 canonical 化由调用方负责。
 */
public record EnvironmentCapabilityExecutionRequest(
    EnvironmentCapabilityDescriptor descriptor,
    EnvironmentCapabilityCall call,
    Duration timeout,
    Path workdir) {

  public EnvironmentCapabilityExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call");
    call = call.validateFor(descriptor);
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    if (workdir != null && !workdir.isAbsolute()) {
      throw new IllegalArgumentException("workdir must be an absolute path");
    }
  }

  /** 返回请求覆盖值或 descriptor 的默认超时。 */
  public Duration effectiveTimeout() {
    return timeout.isZero() ? descriptor.timeout() : timeout;
  }
}
