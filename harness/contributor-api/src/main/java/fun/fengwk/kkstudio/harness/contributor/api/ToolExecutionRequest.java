package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 提交给 Tool SPI 的单次执行请求。
 *
 * <p>构造时先对 {@code call} 做 schema 驱动的静默数字归一化（如整数字符串→integer）再做严格校验，请求持有的 {@code call} 是归一化后的 {@link
 * ToolCall}；执行路径读到的 {@code argumentsJson} 保证满足目标数值类型。
 *
 * <p>{@code workdir} 是已 canonicalize 的 invocation workspace（可空；非空时必须为 absolute path）。
 */
public record ToolExecutionRequest(
    ToolDescriptor descriptor,
    ToolCall call,
    Duration timeout,
    ToolExecutionContext context,
    Path workdir) {

  /** 无 invocation context / workspace 的直调请求。 */
  public ToolExecutionRequest(ToolDescriptor descriptor, ToolCall call, Duration timeout) {
    this(descriptor, call, timeout, null, null);
  }

  /** 带 execution context 的请求；不携带 invocation workspace。 */
  public ToolExecutionRequest(
      ToolDescriptor descriptor, ToolCall call, Duration timeout, ToolExecutionContext context) {
    this(descriptor, call, timeout, context, null);
  }

  public ToolExecutionRequest {
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

  /** 返回请求覆盖值或 descriptor 默认值。 */
  public Duration effectiveTimeout() {
    return timeout.isZero() ? descriptor.timeout() : timeout;
  }
}
