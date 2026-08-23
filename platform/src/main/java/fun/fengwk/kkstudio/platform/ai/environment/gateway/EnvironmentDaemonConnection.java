package fun.fengwk.kkstudio.platform.ai.environment.gateway;

/** 进程内对单个 Environment Daemon WebSocket 连接的 transport 句柄。 */
public interface EnvironmentDaemonConnection {

  /** 仅在该 transport 连接内稳定；不是持久的 Environment 身份。 */
  String connectionId();

  boolean isOpen();

  /** 发送一帧完整编码的 daemon 协议文本。 */
  void sendText(String text);

  void close();
}
