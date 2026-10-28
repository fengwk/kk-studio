package fun.fengwk.kkstudio.ide.mux;

import java.util.ArrayDeque;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;

/**
 * 调用方持有的一条 Server 发起逻辑流。
 *
 * <p>OPEN 只有在真正进入物理写入时才分配连续 streamId，因此 {@link #streamId()} 在 {@link #opened()} 完成前为空是正常的。发送方法返回的
 * future 在对应帧完成物理写入后成功；没有 credit、尚未 READY 或管理帧优先级更高时 future 保持 pending。取消尚未开始写入的 DATA future
 * 会把它移出有界队列；已交给物理传输的帧不能被取消或撤回。物理写失败后整条连接 fail-stop，future 全部异常结束，且不会重放。
 *
 * <p>HTTP：请求方向使用 {@link #sendHttpData(byte[])} 与 {@link #sendEnd()}；响应方向在收到 END 且下游 flush 成功后调用
 * {@link #complete()}，失败调用 {@link #reset(String, String)}。WS：使用 {@link #sendWsData(boolean,
 * boolean, byte[])} 与 {@link #sendClose(int, String)}。
 */
public final class MuxStream {

  final MuxConnection connection;
  final MuxOpen openRequest;
  final MuxStreamListener listener;
  final ArrayDeque<MuxConnection.Outbound> outbound = new ArrayDeque<>();
  final CompletableFuture<Void> opened = new CompletableFuture<>();
  final CompletableFuture<Void> closed = new CompletableFuture<>();

  long streamId;
  boolean active = true;
  boolean ready;
  boolean headersReceived;
  boolean inboundEnded;
  boolean outboundEnded;
  boolean completeSent;
  boolean closeSent;
  boolean closeReceived;
  boolean closeWriteCompleted;
  boolean wsMessageOpen;
  MuxDataKind wsMessageKind;
  boolean wsOutboundMessageOpen;
  MuxDataKind wsOutboundMessageKind;
  boolean resetSent;
  ScheduledFuture<?> closeTimer;
  long sendUnacked;
  long receiveUnacked;
  long pendingWindow;
  int unconsumedFrames;
  long queuedDataCost;

  MuxStream(MuxConnection connection, MuxOpen openRequest, MuxStreamListener listener) {
    this.connection = connection;
    this.openRequest = openRequest;
    this.listener = listener;
  }

  /** 原始 OPEN 元数据。 */
  public MuxOpen openRequest() {
    return openRequest;
  }

  /** HTTP 或 WS。 */
  public MuxMode mode() {
    return openRequest.mode();
  }

  /** OPEN 完成物理写入后分配的 streamId；在此之前为空。 */
  public OptionalLong streamId() {
    return connection.streamIdOf(this);
  }

  /** OPEN 物理写入成功。 */
  public CompletionStage<Void> opened() {
    return opened;
  }

  /** 流正常关闭或被连接失败/关闭终结。 */
  public CompletionStage<Void> closed() {
    return closed;
  }

  /** 当前仍可接收/发送逻辑流帧。 */
  public boolean isOpen() {
    return connection.isStreamOpen(this);
  }

  /** 是否已收到 READY。 */
  public boolean isReady() {
    return connection.isStreamReady(this);
  }

  /** HTTP 请求体分片；返回物理写入完成 future。 */
  public CompletableFuture<Void> sendHttpData(byte[] payload) {
    return connection.sendData(this, MuxDataKind.HTTP, false, payload);
  }

  /**
   * WS 消息分段；{@code text} 选择 TEXT/BINARY，{@code fin} 标记消息结束。
   *
   * <p>必须等 {@link MuxStreamListener#onHeaders} 报告 101 后调用；同一条消息内各段的 TEXT/BINARY 类型必须一致。
   */
  public CompletableFuture<Void> sendWsData(boolean text, boolean fin, byte[] payload) {
    return connection.sendData(
        this, text ? MuxDataKind.WS_TEXT : MuxDataKind.WS_BINARY, fin, payload);
  }

  /** HTTP 请求方向 END；请求 END 不结束响应。 */
  public CompletableFuture<Void> sendEnd() {
    return connection.sendHttpEnd(this);
  }

  /**
   * HTTP 响应已成功消费并 flush；发送 COMPLETE 并结束该流。COMPLETE 要求请求 END 已排队、响应 END 已收到且响应 DATA
   * 已全部消费并归还额度，并严格排在同流 END 之后。
   */
  public CompletableFuture<Void> complete() {
    return connection.sendComplete(this);
  }

  /**
   * WS 主动关闭；等所有先前 DATA 的物理写完成回调返回后才写 CLOSE，已交给传输的 DATA 不能撤回。
   *
   * <p>等待对端 CLOSE，默认最多 5 秒（可由连接构造参数调整）。
   */
  public CompletableFuture<Void> sendClose(int code, String reason) {
    return connection.sendClose(this, new MuxClose(code, reason));
  }

  /** 本端放弃该流；可随时调用，未发送数据直接丢弃。 */
  public CompletableFuture<Void> reset(String code, String message) {
    return connection.resetStream(this, new MuxReset(code, message));
  }

  @Override
  public String toString() {
    return "MuxStream{" + openRequest.mode() + ",streamId=" + streamId + ",active=" + active + '}';
  }
}
