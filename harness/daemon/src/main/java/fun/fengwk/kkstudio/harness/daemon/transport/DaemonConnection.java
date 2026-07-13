package fun.fengwk.kkstudio.harness.daemon.transport;

import java.util.concurrent.CompletionStage;

/** 单个已建立的 Daemon WebSocket 连接。 */
public interface DaemonConnection {

  /** 异步发送一条完整文本帧。 */
  CompletionStage<Void> sendText(String message);

  /** 主动关闭连接。 */
  void close();

  /** 连接是否仍然可发送。 */
  boolean isOpen();
}
