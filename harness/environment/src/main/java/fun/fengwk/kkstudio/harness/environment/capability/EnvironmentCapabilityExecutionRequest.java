package fun.fengwk.kkstudio.harness.environment.capability;

import java.time.Duration;
import java.util.Objects;

/**
 * 发给本地或远端 Environment Capability 的单次执行请求。
 *
 * <p>{@code timeout} 是已经解析完成的唯一有效执行超时：{@link Duration#ZERO} 表示本次调用没有执行 deadline。执行层不得再做
 * fallback、min clamp 或二次默认值解析。
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
}
