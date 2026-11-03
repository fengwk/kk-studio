package fun.fengwk.kkstudio.platform.daemon.tunnel;

import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/**
 * 绑定一次 Daemon 连接的数据面会话。
 *
 * <p>句柄在打开时固定到当前连接。{@link #identity()} 只标识 Environment 与 Daemon
 * 进程；同一进程重连时身份值可以不变，但连接句柄已经更换，已打开的交换和字节流不迁移。会话开始排空后拒绝新建，已经打开的流仍可完成。
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
   * 打开一次 extended CONNECT WebSocket 隧道；request.method 必须是 GET。
   *
   * <p>响应 200 表示隧道建立，{@link TunnelExchange#stream()} 为原始双向字节流。
   *
   * @param request 必须以 GET 为方法的已校验请求，不能为 {@code null}
   * @return 绑定当前连接代际的交换
   * @throws IllegalArgumentException 请求为 {@code null} 或请求方法不是 GET
   * @throws IllegalStateException 会话已排空或已断开
   */
  TunnelExchange openWebSocket(TunnelHttpRequest request);
}
