package fun.fengwk.kkstudio.harness.provider.transport;

/**
 * HTTP/SSE 流传输接收器。
 *
 * <p>生命周期与并发契约：
 *
 * <ul>
 *   <li>{@link #onOpen}：在 HTTP 握手成功（2xx 且 text/event-stream）后触发一次。
 *   <li>{@link #onEvent}：每个完整解析出的 SSE 事件触发一次。
 *   <li>{@link #onComplete}：流正常到达 EOF 且所有未完结的待发事件均交付后触发，至多一次。
 *   <li>{@link #onFailure}：遇到不可恢复的传输失败时触发，至多一次。与 {@code onComplete} 互斥。
 *   <li>流被取消后不再触发任何回调；正常取消静默退出。
 * </ul>
 */
public interface HttpSseCallback {

  /** 接收 HTTP 握手元数据。 */
  void onOpen(HttpOpenMetadata metadata);

  /** 接收一个解析完成的增量事件。 */
  void onEvent(ServerSentEvent event);

  /** 正常结束到达流 EOF。终态回调，至多一次。 */
  void onComplete();

  /** 遇到不可恢复的终止错误。终态回调，至多一次。 */
  void onFailure(TransportException error);
}
