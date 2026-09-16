package fun.fengwk.kkstudio.harness.daemon.transport;

import java.util.concurrent.CompletionStage;

/**
 * Daemon Runtime 的可替换 WebSocket transport 边界。
 *
 * <p>测试可提供内存实现；生产入口使用 {@link OkHttpWebSocketTransport} 实现，并要求握手协商 {@code permessage-deflate}。
 */
public interface DaemonTransport extends AutoCloseable {

  /** 建立一条连接，并将后续入站事件投递给 listener。 */
  CompletionStage<DaemonConnection> connect(DaemonTransportListener listener);

  @Override
  default void close() {}
}
