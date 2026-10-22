package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 工具实现 SPI；Runtime 负责权限、持久化和执行调度。
 *
 * <p>{@link #execute} 是阻塞 SPI：Runtime 在每次调用独占的一条虚拟线程上调用它，返回即终态。取消、Stop 与租约丢失都表现为对该线程的
 * interrupt：实现必须响应中断，关闭自己持有的资源并抛出 {@link InterruptedException}；执行体退出之前，Runtime 不会释放该调用所钉住的任何 本地资源。
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
   * 返回本 Tool 拥有的可选历史 action 渲染器；默认 absent，表示 Runtime 使用通用自然语言回退。
   *
   * <p>渲染器必须是无副作用纯函数：Runtime 在成功 ProviderResponse 冻结前调用它，并容忍其返回 empty / 抛出异常。
   */
  default Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.empty();
  }

  /**
   * 阻塞执行并返回终态结果。
   *
   * <p>正常的业务失败返回 error {@link ToolResult}；无法完成时抛出运行时异常（Runtime 把它收敛为不确定终态）。需要流式进度的工具通过 {@code
   * progress} 发布非终止的 {@link ToolResult}（不得含 binary / resource 内容）；不流式的工具忽略它。
   *
   * @throws InterruptedException 执行被取消（Stop、租约丢失或进程关闭）
   */
  ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;
}
