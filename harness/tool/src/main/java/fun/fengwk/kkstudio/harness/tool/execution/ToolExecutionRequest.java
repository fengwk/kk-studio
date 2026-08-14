package fun.fengwk.kkstudio.harness.tool.execution;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 提交给 Tool SPI 的单次执行请求。
 *
 * <p>{@code workdir} 是 daemon 已 canonicalize 的 invocation workspace（可空；非空时必须为 absolute
 * path，canonical 化由调用方完成）。
 */
public record ToolExecutionRequest(
    ToolDescriptor descriptor,
    ToolCall call,
    Duration timeout,
    ToolExecutionContext context,
    Path workdir) {

  /** 无 invocation context / workspace 的 transport 直调请求（workdir 由执行端默认解析）。 */
  public ToolExecutionRequest(ToolDescriptor descriptor, ToolCall call, Duration timeout) {
    this(descriptor, call, timeout, null, null);
  }

  /**
   * 带 durable execution context 的 Platform/transport 请求；不携带 invocation workspace（workdir 由执行端默认解析）。
   */
  public ToolExecutionRequest(
      ToolDescriptor descriptor, ToolCall call, Duration timeout, ToolExecutionContext context) {
    this(descriptor, call, timeout, context, null);
  }

  public ToolExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call");
    call.validateFor(descriptor);
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
