package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.Objects;

/**
 * 提交给 Tool SPI 的单次执行请求。
 *
 * <p>构造时先对 {@code call} 做 schema 驱动的静默归一化（数字字符串→目标数值、可缺省属性的显式 null→缺省）再做严格校验，请求持有的 {@code call}
 * 是归一化后的 {@link ToolCall}；执行路径读到的 {@code argumentsJson} 保证满足目标 schema。
 *
 * <p>{@code timeout} 是执行前解析完成的唯一有效执行超时（由 {@link Tool#resolveTimeout(ToolCall)} 给出）： {@link
 * Duration#ZERO} 表示没有执行 deadline。执行层不得再做默认值回落、上限截断或取最小值。
 *
 * <p>请求不携带 workdir：目录只存在于具体工具 arguments 中，框架不把它提升为通用执行状态，也不提供隐藏默认目录。
 */
public record ToolExecutionRequest(
    ToolDescriptor descriptor, ToolCall call, Duration timeout, ToolExecutionContext context) {

  /** 无 invocation context 的直调请求。 */
  public ToolExecutionRequest(ToolDescriptor descriptor, ToolCall call, Duration timeout) {
    this(descriptor, call, timeout, null);
  }

  public ToolExecutionRequest {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    call = Objects.requireNonNull(call, "call");
    call = call.validateFor(descriptor);
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
  }
}
