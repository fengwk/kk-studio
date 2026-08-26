package fun.fengwk.kkstudio.harness.tool.capability;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

/**
 * Environment Capability 的传输端口。
 *
 * <p>实现只负责向冻结的 Environment binding 发送 capability invocation 并透传异步事件，不拥有 durable invocation 状态机。
 */
public interface EnvironmentCapabilityTransport {

  /**
   * 启动一次 capability invocation。
   *
   * <p>发送前抛出 {@link EnvironmentCapabilityBusyException} 或 {@link
   * EnvironmentCapabilityUnavailableException} 时，调用肯定未执行；抛出 {@link
   * EnvironmentCapabilitySendUncertainException} 时，调用可能已被接受，调用方不得重放。成功启动后，事件序列固定为 {@code PARTIAL*
   * -> terminal/cancel}，实现必须按 {@code PARTIAL* -> exactly one terminal} 顺序透传 listener 事件；远程 {@code
   * FAILED} 和 {@code CANCELLED} 分别透传为 {@link
   * EnvironmentCapabilityExecutionListener#onError(Throwable)} 携带 {@link
   * EnvironmentCapabilityFailedException} 和 {@link
   * EnvironmentCapabilityCancelledException}。terminal 之后到达的 late event 必须丢弃；返回句柄的 cancel 请求必须透传为远程
   * cancel，partial 与 terminal 的顺序不得重排。
   *
   * @param binding 冻结的完整 Environment binding，不得为 null
   * @param request capability execution request
   * @param listener 接收 partial 与唯一 terminal 事件的 listener
   * @return 可取消的 execution handle
   * @throws EnvironmentCapabilityBusyException 发送前容量冲突，调用肯定未执行
   * @throws EnvironmentCapabilityUnavailableException 发送前目标不可用，调用肯定未执行
   * @throws EnvironmentCapabilitySendUncertainException 发送结果不确定，调用可能已被接受且不得重放
   */
  EnvironmentCapabilityExecutionHandle invoke(
      EnvironmentBinding binding,
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener);
}
