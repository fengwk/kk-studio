package fun.fengwk.kkstudio.ide.mux;

/**
 * 单条逻辑流的入站事实回调；回调按 wire 顺序在连接状态锁外投递。
 *
 * <p>{@link #onData} 只在数据真实可交付时回调；调用方必须在下游实际写入/消费后调用 {@link MuxReceivedData#consumed()}，引擎才会归还精确
 * credit。回调抛出的运行时异常会使整条连接 fail-stop。
 */
public interface MuxStreamListener {

  /** Daemon 接受 OPEN。 */
  default void onReady(MuxStream stream) {}

  /** 收到唯一的响应头 / WS 握手结果。 */
  default void onHeaders(MuxStream stream, MuxHeaders headers) {}

  /** 收到 HTTP body 或 WS 消息分段。 */
  default void onData(MuxStream stream, MuxReceivedData data) {}

  /** 收到 HTTP 响应 END（请求 END 不会触发该回调）。 */
  default void onEnd(MuxStream stream) {}

  /** 收到对端 RESET；流已结束。 */
  default void onReset(MuxStream stream, MuxReset reset) {}

  /** 收到 WS CLOSE；流将在双向关闭完成后回收。 */
  default void onClose(MuxStream stream, MuxClose close) {}
}
