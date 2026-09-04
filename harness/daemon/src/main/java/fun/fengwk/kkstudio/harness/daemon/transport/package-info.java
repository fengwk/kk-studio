/**
 * Daemon 底层传输抽象与基于 JDK {@link java.net.http.WebSocket} 的生产实现。
 *
 * <p>本包定义 transport 连接接口 {@link fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection}、传输抽象
 * {@link fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport} 以及监听回调 {@link
 * fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener}。
 *
 * <p>生产实现 {@link fun.fengwk.kkstudio.harness.daemon.transport.JdkWebSocketTransport}
 * 仅接受入站文本帧，在分片累积长度超过配置上限（默认 16 MiB 字符）或接收到二进制帧时，确定性触发一次 RFC 6455 close code 1008 (policy violation)
 * 关闭并通知断开。
 *
 * <p>严格区分 transport listener 监听接口、连接事件回调动作与 Invocation journal 执行事实；连接仅作为消息传输管道，不作为执行状态的事实源。
 */
package fun.fengwk.kkstudio.harness.daemon.transport;
