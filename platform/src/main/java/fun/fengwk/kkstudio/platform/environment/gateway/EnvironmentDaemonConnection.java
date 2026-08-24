package fun.fengwk.kkstudio.platform.environment.gateway;

/** 进程内对单个 Environment Daemon WebSocket 连接的 transport 句柄。 */
public interface EnvironmentDaemonConnection {

  /** 仅在该 transport 连接内稳定；不是持久的 Environment 身份。 */
  String connectionId();

  boolean isOpen();

  /**
   * 非阻塞入队一帧完整编码的 daemon 协议文本。
   *
   * @return 已进入该连接的有界串行发送队列时返回 {@code true}；连接关闭或队列达到帧数/字节上限时返回 {@code false}
   */
  boolean sendText(String text);

  /** 禁止新入队，按顺序发送已接受帧后关闭；默认 transport 不支持 drain 时退化为立即关闭。 */
  default void closeAfterFlush() {
    close();
  }

  void close();
}
