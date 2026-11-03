/**
 * Environment 数据面的传输无关隧道端口。
 *
 * <p>本包只定义本节点会话查询、HTTP 交换与 WebSocket 隧道的窄契约和值对象。它不选择 HTTP/2、WebSocket 或其他引擎，也不包含运行时 fallback、网络 I/O
 * 或生产实现。
 *
 * <p>{@link EnvironmentTunnel#openWebSocket(TunnelHttpRequest)} 基于 extended CONNECT，{@code
 * request.method} 必须是 GET（否则抛出 {@link IllegalArgumentException}）；响应 200 表示隧道建立，{@link
 * TunnelExchange#stream()} 为原始双向字节流。端口不理解具体应用协议，由调用方组装路径，传输只负责映射。
 *
 * <p>非法参数在调用线程同步抛出 {@link IllegalArgumentException}。同一流的每个方向最多一个未完成操作，重复调用是编程错误，同步抛出 {@link
 * IllegalStateException}。正常 EOF 后每次 {@code read} 都完成 {@code null}；{@code endOutput}
 * 幂等，排在已有写入之后并返回同一完成结果； {@code close} 幂等，并以 {@link java.util.concurrent.CancellationException}
 * 结束尚未完成的操作。会话断开使全部子流失败，模拟断开使用 {@link java.io.IOException}。传输实现使用标准协议窗口，不在本端口增加第二套 credit。
 */
package fun.fengwk.kkstudio.platform.daemon.tunnel;
