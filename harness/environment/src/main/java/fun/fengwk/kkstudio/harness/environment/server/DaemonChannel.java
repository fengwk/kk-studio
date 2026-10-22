package fun.fengwk.kkstudio.harness.environment.server;

/**
 * 一条 daemon 连接的服务端传输窄端口。
 *
 * <p>实现只负责文本帧的递交与关闭，不解释协议、不保存会话状态；{@link #offerText(String)} 的结果语义见 {@link
 * DaemonOfferResult}。实现必须允许在任意线程调用，并在 {@link #close()} / {@link #closeAfterFlush()} 后保持幂等。
 */
public interface DaemonChannel {

  /** 连接标识；同一连接在整个生命周期内保持不变，且与非并发重连的新连接互不相同。 */
  String connectionId();

  /** 连接当前是否可发送；返回 false 表示后续递交都会被判定为 {@link DaemonOfferResult#CLOSED}。 */
  boolean isOpen();

  /**
   * 尝试把一条完整文本帧交给传输。
   *
   * <p>本地容量拒绝（{@link DaemonOfferResult#BUSY}）与连接不可用（{@link DaemonOfferResult#CLOSED}）都表示该帧
   * 肯定未发送，且都不得使连接失效； 只有传输自身发送失败才体现为连接失效。
   */
  DaemonOfferResult offerText(String text);

  /** 已入队帧尽量发送后关闭连接。 */
  void closeAfterFlush();

  /** 立即关闭连接；必须幂等。 */
  void close();
}
