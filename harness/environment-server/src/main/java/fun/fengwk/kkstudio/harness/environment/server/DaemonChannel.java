package fun.fengwk.kkstudio.harness.environment.server;

/**
 * 一条 daemon 连接的服务端传输窄端口。
 *
 * <p>实现只负责文本帧的发送与关闭，不解释协议、不保存会话状态；{@link #sendText(String)} 的返回值语义见 {@link
 * DaemonSendOutcome}。实现必须允许在任意线程调用，并在 {@link #close()} / {@link #closeAfterFlush()} 后保持幂等。
 */
public interface DaemonChannel {

  /** 连接标识；同一连接在整个生命周期内保持不变，且与非并发重连的新连接互不相同。 */
  String connectionId();

  /** 连接当前是否可发送；返回 false 表示后续发送都会被判定为未发送。 */
  boolean isOpen();

  /**
   * 尝试发送一个完整文本帧。
   *
   * @return true 表示帧已交给传输；false 表示传输拒绝接受该帧，本次写入不可确认，核心将连接判为不可用
   */
  boolean sendText(String text);

  /** 已入队帧尽量发送后关闭连接。 */
  void closeAfterFlush();

  /** 立即关闭连接；必须幂等。 */
  void close();
}
