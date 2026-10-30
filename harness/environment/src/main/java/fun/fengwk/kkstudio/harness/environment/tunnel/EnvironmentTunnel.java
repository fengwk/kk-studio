package fun.fengwk.kkstudio.harness.environment.tunnel;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

/**
 * 绑定一次 Daemon 连接的数据面会话。
 *
 * <p>句柄在打开时固定到 {@link #identity()}。同一 Daemon 进程重连会产生新代际，已打开的交换和字节流不迁移到新句柄。会话开始排空后拒绝新建，已经打开的流仍可完成。
 */
public interface EnvironmentTunnel {

  /** 本隧道绑定且不可替换的连接代际。 */
  DaemonConnectionIdentity identity();

  /**
   * 打开一次 HTTP 交换。
   *
   * <p>方法同步返回交换句柄，不等待响应头；响应头通过 {@link TunnelExchange#responseHeaders()} 异步到达。
   *
   * @param request 已校验的 origin-form 请求，不能为 {@code null}
   * @return 绑定当前连接代际的交换
   * @throws IllegalArgumentException 请求为 {@code null}
   * @throws IllegalStateException 会话已排空或已断开
   */
  TunnelExchange openHttp(TunnelHttpRequest request);

  /**
   * 打开一条双向原始字节流。
   *
   * @param target 只允许 {@code ide/<canonical UUID>}，不能携带主机或端口
   * @return 绑定当前连接代际的字节流
   * @throws IllegalArgumentException {@code target} 不是唯一允许的形式
   * @throws IllegalStateException 会话已排空或已断开
   */
  TunnelStream openRaw(String target);
}
