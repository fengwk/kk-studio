package fun.fengwk.kkstudio.harness.tool.execution;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.Objects;

/** 提交给 Tool SPI 的单次执行请求。 */
public record ToolExecutionRequest(
    ToolDescriptor descriptor, ToolCall call, Duration timeout, ToolExecutionContext context) {

  /** Environment Tool 请求；Daemon 执行不接收 durable Platform 归属。 */
  public ToolExecutionRequest(ToolDescriptor descriptor, ToolCall call, Duration timeout) {
    this(descriptor, call, timeout, null);
  }

  public ToolExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call");
    call.validateFor(descriptor);
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
  }

  /** 返回请求覆盖值或 descriptor 默认值。 */
  public Duration effectiveTimeout() {
    return timeout.isZero() ? descriptor.timeout() : timeout;
  }
}
