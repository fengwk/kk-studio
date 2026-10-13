package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;

/**
 * Daemon 出站报文窄端口：把一条已构造好的消息交给当前活跃连接，不感知协议状态、重连或代际。
 *
 * <p>返回 {@code false} 表示当前没有可递交的连接（报文确定未发送，例如连接尚未 READY 或已断开）；调用方据此决定重放或放弃。由连接会话实现，
 * 使调用执行、更新编排只依赖这一条出口，而不持有连接状态。
 */
@FunctionalInterface
interface DaemonMessageSink {

  boolean send(DaemonMessageType messageType, String invocationId, String payloadJson);
}
