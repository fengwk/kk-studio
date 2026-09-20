package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.Objects;

/**
 * 工具实现 SPI；Runtime 负责权限、持久化和执行调度。
 *
 * <p>{@link #execute(ToolExecutionRequest, ToolExecutionListener)} 必须是启动式、快速返回的 async SPI。
 * 允许在调用线程中触发同步 callback，但必须由 Platform 对同步 callback 进行 gate 处理。
 */
public interface Tool {

  /** 返回该工具对模型和执行器公开的描述。 */
  ToolDescriptor descriptor();

  /** 声明工具执行所需的前置依赖与状态访问需求。默认无特殊要求。 */
  default ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  /**
   * 从归一化后的调用解析本次执行的唯一有效超时，默认返回 definition 的 {@link ToolDescriptor#defaultTimeout()}。
   *
   * <p>只有真正拥有 arguments 级超时契约的工具才覆盖此方法：覆盖实现返回显式值，缺省时返回 definition 默认值。返回值是执行前解析的
   * 唯一结果，下游层不得再做默认值回落、上限截断或取最小值；arguments 级超时非法（例如非正数的显式超时）时必须抛 {@link IllegalArgumentException}
   * 而不是退回默认值，Platform 会把它收敛为确定性的请求拒绝。
   *
   * <p>{@link Duration#ZERO} 表示没有执行 deadline。
   */
  default Duration resolveTimeout(ToolCall call) {
    Objects.requireNonNull(call, "call");
    return descriptor().defaultTimeout();
  }

  /**
   * 启动执行并返回可取消句柄。
   *
   * <p>必须为启动式、快速返回的 async SPI。允许同步 callback，但由 Platform gate。
   */
  ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
}
