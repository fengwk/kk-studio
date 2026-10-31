/**
 * 基于 Jetty 12 的 Environment Daemon 传输层实现。
 *
 * <p>本包提供 Studio 与 Daemon 之间的底层物理传输与 HTTP/2 会话管道：
 *
 * <ul>
 *   <li>{@link WebSocketEndPoint}：适配 WebSocket 双向管道至 Jetty {@link org.eclipse.jetty.io.EndPoint}，
 *       采用单槽 demand 驱动流控与回压，支持 flush-mode 缓冲区。
 *   <li>{@link DaemonHttp2ClientSession}：管理 HTTP/2 客户端会话生命周期、15 秒 PING / 30 秒 ACK 超时存活检测， 以及 GOAWAY
 *       优雅断开与连接失败传播。
 *   <li>{@link DaemonControlStream}：绑定 {@code POST /control} 单向/双向控制通道， 按 Envelope 成帧并提供 256 帧 / 16
 *       MiB 预算的 BUSY 背压控制与 10 秒写入超时。
 *   <li>{@link DaemonEnvironmentTunnel}：提供流式 HTTP 交换与基于 RFC 8441 extended CONNECT 的 WebSocket
 *       原始双向字节流隧道。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.environment.transport;
