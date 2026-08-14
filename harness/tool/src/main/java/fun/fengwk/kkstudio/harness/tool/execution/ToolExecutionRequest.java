package fun.fengwk.kkstudio.harness.tool.execution;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 提交给 Tool SPI 的单次执行请求。 */
public record ToolExecutionRequest(
    ToolDescriptor descriptor,
    ToolCall call,
    Duration timeout,
    ToolExecutionContext context,
    Path workdir) {

  /** Environment Tool 请求；Daemon 执行不接收 durable Platform 归属，也无 invocation workspace。 */
  public ToolExecutionRequest(ToolDescriptor descriptor, ToolCall call, Duration timeout) {
    this(descriptor, call, timeout, null, null);
  }

  /** 带 durable execution context 的请求（Platform 工具；workdir 由 Platform 自行解析）。 */
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
      throw new IllegalArgumentException("workdir must be an absolute canonical path");
    }
  }

  /** 返回请求覆盖值或 descriptor 默认值。 */
  public Duration effectiveTimeout() {
    return timeout.isZero() ? descriptor.timeout() : timeout;
  }
}
