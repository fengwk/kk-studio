package fun.fengwk.kkstudio.harness.environment.server.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;

import java.util.UUID;

/**
 * environment-server 的窄终端响应回调：接收某个认证 READY 绑定上的 Daemon shell 事件。
 *
 * <p>实现必须非阻塞，且只在调用方保证的顺序边界内消费；回调不逐包查询 PG，也不把控制 payload 记入日志。租约与实例身份由服务端在调用前完成认证，
 * 实现无需也不得自行从浏览器输入重新推导。
 */
@FunctionalInterface
public interface EnvironmentTerminalListener {

  /**
   * 投递一次终端响应。
   *
   * @param leaseToken 服务端认证的 READY 租约 token
   * @param daemonInstanceId 服务端认证的 Daemon 实例身份
   * @param response 终端控制响应
   */
  void onTerminalResponse(UUID leaseToken, UUID daemonInstanceId, TerminalResponse response);
}
