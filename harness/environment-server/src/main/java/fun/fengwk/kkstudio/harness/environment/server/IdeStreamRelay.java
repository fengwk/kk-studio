package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamControlFrame;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamCreditWindow;

import java.util.Objects;

/**
 * 反向数据面的纯状态机：一条 HTTP exchange / IDE WebSocket 对应一侧（Server Gateway）的帧收发与 credit 裁决。
 *
 * <p>双向各有独立 credit 窗口：{@code toDaemon} 限制本端可发送但未获 Daemon ACK 的字节，{@code fromDaemon} 限制本端已接收 但未向
 * Daemon ACK 的字节。任何超窗发送、超窗/负数 ACK、未知帧或已关闭后的操作都 fail-closed（抛 {@link
 * DaemonProtocolException}），保证无无界排队、无「先 ACK 后缓冲」。字节只在此处流转，绝不进入 SQL/PG。
 *
 * <p>本类不做重排：HTTP body/END 与 WS 分段按线序返回，由适配层按 FIN 重组为单条 IDE 消息；它也不持有真实传输，所有出站帧经 {@link Sink} 交给适配层。
 */
public final class IdeStreamRelay {

  /** 出站 wire 帧的窄出口。 */
  public interface Sink {

    void sendText(String frame);

    void sendBinary(byte[] frame);
  }

  private final Sink sink;
  private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
  private final IdeStreamCreditWindow toDaemon = new IdeStreamCreditWindow();
  private final IdeStreamCreditWindow fromDaemon = new IdeStreamCreditWindow();
  private boolean closed;

  public IdeStreamRelay(Sink sink) {
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /** 本端仍可发送给 Daemon 的字节数（适配层据此等待 credit，而非无界排队）。 */
  public long sendableToDaemon() {
    return toDaemon.available();
  }

  /** 本端仍可接收但尚未 ACK 的字节数。 */
  public long receivableFromDaemon() {
    return fromDaemon.available();
  }

  public boolean isClosed() {
    return closed;
  }

  /** 发送一个 HTTP body 分片（≤65536）。超窗即 fail-closed。 */
  public void sendHttpBody(byte[] payload) {
    requireOpen();
    Objects.requireNonNull(payload, "payload");
    if (payload.length > DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES) {
      throw new DaemonProtocolException(
          "http body chunk exceeds " + DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES);
    }
    if (!toDaemon.onSend(payload.length)) {
      throw new DaemonProtocolException("http body chunk exceeds available send credit");
    }
    sink.sendBinary(codec.encodeHttpBody(payload));
  }

  /** 发送 HTTP END（半关闭；请求 END 不等于响应结束）。 */
  public void sendHttpEnd() {
    requireOpen();
    sink.sendBinary(codec.encodeHttpEnd());
  }

  /**
   * 发送一个 WS 分段（≤65536，{@code fin} 标记本消息是否结束）。
   *
   * <p>适配层把一条 WS 消息切成逐段调用本方法，并在每段前用 {@link #sendableToDaemon()} 等待 credit：本方法只裁决单段，超窗即 fail-closed
   * 且不发送，因此不会像整条消息一次性发送那样在发送数段后才因 credit 不足产生「部分已发」的副作用。
   */
  public void sendWsSegment(boolean text, boolean fin, byte[] chunk) {
    requireOpen();
    Objects.requireNonNull(chunk, "chunk");
    if (chunk.length > DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES) {
      throw new DaemonProtocolException(
          "ws segment exceeds " + DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES + " bytes");
    }
    if (!toDaemon.onSend(chunk.length)) {
      throw new DaemonProtocolException("ws segment exceeds available send credit");
    }
    sink.sendBinary(codec.encodeWsSegment(text, fin, chunk));
  }

  /** 处理 Daemon 的文本控制帧。 */
  public IdeStreamControlFrame receiveText(String json) {
    requireOpen();
    return codec.decodeControl(json);
  }

  /**
   * 处理 Daemon 的二进制帧：HTTP body / WS 分段计入接收 credit（适配层消费后调 {@link #ackFromDaemon(int)}），ACK 释放发送
   * credit。
   */
  public IdeStreamBinaryFrame receiveBinary(byte[] frame) {
    requireOpen();
    IdeStreamBinaryFrame decoded = codec.decodeBinary(frame);
    if (decoded instanceof IdeStreamBinaryFrame.Ack ack) {
      toDaemon.onAck(ack.byteCount());
      return ack;
    }
    int bytes =
        switch (decoded) {
          case IdeStreamBinaryFrame.HttpBody body -> body.bytes().length;
          case IdeStreamBinaryFrame.WsSegment segment -> segment.bytes().length;
          case IdeStreamBinaryFrame.HttpEnd ignored -> 0;
          case IdeStreamBinaryFrame.Ack ignored -> 0;
        };
    if (bytes > 0 && !fromDaemon.onSend(bytes)) {
      throw new DaemonProtocolException("daemon exceeded the receive credit window");
    }
    return decoded;
  }

  /** 本端已真实消费 {@code consumed} 字节：给 Daemon 回 ACK，释放其发送窗口不再超过初始 credit。 */
  public void ackFromDaemon(int consumed) {
    requireOpen();
    if (consumed < 0) {
      throw new DaemonProtocolException("ack byte count must not be negative");
    }
    if (consumed == 0) {
      return;
    }
    fromDaemon.onAck(consumed);
    sink.sendBinary(codec.encodeAck(consumed));
  }

  /** 关闭该流：此后任何收发都 fail-closed（取消/错误/关闭双向收尾后调用）。 */
  public void close() {
    closed = true;
  }

  private void requireOpen() {
    if (closed) {
      throw new DaemonProtocolException("stream relay is closed");
    }
  }
}
