package fun.fengwk.kkstudio.harness.tool.remote;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

/**
 * 传输无关的远程 Tool 调用端口。
 *
 * <p>实现只负责连接/协议发送与回调转发，不拥有 durable ToolInvocation 状态机。协议回调必须映射到同一 {@link ToolExecutionListener}
 * 契约：PARTIAL → {@link ToolExecutionListener#onPartial}，COMPLETED → {@link
 * ToolExecutionListener#onComplete}，FAILED → {@link ToolExecutionListener#onError} 并携带 {@link
 * RemoteToolFailedException}，CANCELLED → {@link ToolExecutionListener#onError} 并携带 {@link
 * RemoteToolCancelledException}。
 */
public interface RemoteToolTransport {

  /**
   * 发送远程 INVOKE 并注册 listener。
   *
   * @param environmentId 目标 Environment 的 canonical 路由身份；display name 不得参与路由。
   * @return 可取消句柄；CANCEL 映射为远程 CANCEL 消息
   * @throws RemoteToolUnavailableException 发送前目标不可用，INVOKE 未发出
   * @throws RemoteToolSendUncertainException 发送结果不确定（可能已投递）
   */
  ToolExecutionHandle invoke(
      EnvironmentId environmentId, ToolExecutionRequest request, ToolExecutionListener listener);
}
