package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

/**
 * Environment Capability 的调用侧传输窄端口。
 *
 * <p>实现只负责向冻结的 {@link EnvironmentBinding} 发送 Capability Invocation 并透传异步事件，不拥有持久化 Invocation 状态机。
 */
public interface EnvironmentCapabilityTransport {

  /**
   * 启动一次 Capability Invocation。
   *
   * <p><b>发送前异常确定性（Pre-send certainty）：</b>
   *
   * <ul>
   *   <li>抛出 {@link EnvironmentCapabilityBusyException} 或 {@link
   *       EnvironmentCapabilityUnavailableException} 时，调用肯定未执行（definitely not executed）；
   *   <li>抛出 {@link EnvironmentCapabilitySendUncertainException} 时，调用可能已被接受（may have been
   *       accepted），调用方严禁重放。
   * </ul>
   *
   * <p><b>事件流式契约（Streaming & Terminal-once contract）：</b> 成功启动并返回执行句柄后，事件流必须严格满足 {@code PARTIAL* ->
   * exactly one terminal} 顺序透传给 {@code listener}：
   *
   * <ul>
   *   <li>远程 {@code FAILED} 和 {@code CANCELLED} 分别透传为 {@link
   *       EnvironmentCapabilityExecutionListener#onError(Throwable)} 并携带 {@link
   *       EnvironmentCapabilityFailedException} 和 {@link EnvironmentCapabilityCancelledException}；
   *   <li>terminal 事件之后到达的任何迟到事件（late event）必须静默丢弃；
   *   <li>返回句柄的 cancel 请求必须透传为远程 cancel，partial 与 terminal 的事件顺序不得重排。
   * </ul>
   *
   * @param binding 冻结的完整 Environment binding，不得为 null
   * @param request Capability execution request
   * @param listener 接收 partial 与唯一 terminal 事件的执行监听器
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
