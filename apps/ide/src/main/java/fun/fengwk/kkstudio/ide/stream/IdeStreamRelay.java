package fun.fengwk.kkstudio.ide.stream;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamControlFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 反向数据面的纯状态机：一条 HTTP exchange / IDE WebSocket 对应一侧（Server Gateway）的帧收发与 credit 裁决。
 *
 * <p>双向各有独立 credit 窗口：{@code toDaemon} 限制本端已发送但未获 Daemon ACK 的数据帧，{@code fromDaemon} 限制本端已接收 但未向
 * Daemon ACK 的数据帧；每帧按 {@link IdeStreamCreditWindow#frameCost(int)} 计价。任何超窗发送、超窗/不足一帧/负数
 * ACK、未知帧或已关闭后的操作都 fail-closed（抛 {@link DaemonProtocolException}），保证无无界排队、无「先 ACK
 * 后缓冲」。字节只在此处流转，绝不进入 SQL/PG。
 *
 * <p>本类不做重排：HTTP body/END 与 WS 分段按线序返回，由适配层按 FIN 重组为单条 IDE 消息；它也不持有真实传输，所有出站帧经 {@link Sink} 交给适配层。
 */
public final class IdeStreamRelay {

  /** 出站 wire 帧的窄出口。 */
  public interface Sink {

    void sendText(String frame);

    void sendBinary(byte[] frame);

    /**
     * 发送一个消费 credit 的 ACK 帧（窄出口）。
     *
     * <p>ACK 只是对已真实交付字节的消费反馈：传输已经断开时该反馈可以安全丢弃，适配层据此避免把它与数据发送混为一谈。
     */
    void sendAcknowledgement(byte[] encodedAck);
  }

  private final Sink sink;
  private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
  private final IdeStreamCreditWindow toDaemon = new IdeStreamCreditWindow();
  private final IdeStreamCreditWindow fromDaemon = new IdeStreamCreditWindow();
  private boolean closed;

  public IdeStreamRelay(Sink sink) {
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /** 现在能否向 Daemon 发送一个载荷为 {@code payloadBytes} 的数据帧（适配层据此等待 credit，而非无界排队）。 */
  public boolean canSendToDaemon(int payloadBytes) {
    return toDaemon.canSendFrame(payloadBytes);
  }

  /** 现在可向 Daemon 发送的单帧最大载荷；剩余 credit 不足一个最小帧时为 0，适配层应等待 ACK。 */
  public int maxPayloadSendableToDaemon() {
    return (int) Math.min(toDaemon.maxFramePayload(), DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES);
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
    if (!toDaemon.onSendFrame(payload.length)) {
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
   * <p>适配层把一条 WS 消息切成逐段调用本方法，并在每段前用 {@link #maxPayloadSendableToDaemon()} 等待 credit：本方法只裁决单段，超窗即
   * fail-closed 且不发送，因此不会像整条消息一次性发送那样在发送数段后才因 credit 不足产生「部分已发」的副作用。
   */
  public void sendWsSegment(boolean text, boolean fin, byte[] chunk) {
    requireOpen();
    Objects.requireNonNull(chunk, "chunk");
    if (chunk.length > DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES) {
      throw new DaemonProtocolException(
          "ws segment exceeds " + DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES + " bytes");
    }
    if (!toDaemon.onSendFrame(chunk.length)) {
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
   * 处理 Daemon 的二进制帧：HTTP body / WS 分段计入接收 credit（适配层消费后调 {@link
   * #acknowledge(IdeStreamBinaryFrame)}），ACK 释放发送 credit。
   */
  public IdeStreamBinaryFrame receiveBinary(byte[] frame) {
    requireOpen();
    IdeStreamBinaryFrame decoded = codec.decodeBinary(frame);
    if (decoded instanceof IdeStreamBinaryFrame.Ack ack) {
      toDaemon.onAck(ack.byteCount());
      return ack;
    }
    OptionalInt payloadBytes = dataPayloadBytes(decoded);
    if (payloadBytes.isPresent() && !fromDaemon.onSendFrame(payloadBytes.getAsInt())) {
      throw new DaemonProtocolException("daemon exceeded the receive credit window");
    }
    return decoded;
  }

  /** 本端已真实消费一个 Daemon 数据帧：按该帧的 credit 代价回 ACK，释放其发送窗口。 */
  public void acknowledge(IdeStreamBinaryFrame consumed) {
    requireOpen();
    int credit =
        IdeStreamCreditWindow.frameCost(
            dataPayloadBytes(consumed)
                .orElseThrow(
                    () -> new IllegalArgumentException("only data frames consume receive credit")));
    fromDaemon.onAck(credit);
    // ACK 只是消费反馈，经独立窄出口发送，传输已断开时适配层可丢弃而不影响已交付的数据。
    sink.sendAcknowledgement(codec.encodeAck(credit));
  }

  /** 数据帧（HTTP body / WS 分段）的载荷字节数；END 与 ACK 不消耗 credit。 */
  private static OptionalInt dataPayloadBytes(IdeStreamBinaryFrame frame) {
    return switch (frame) {
      case IdeStreamBinaryFrame.HttpBody body -> OptionalInt.of(body.bytes().length);
      case IdeStreamBinaryFrame.WsSegment segment -> OptionalInt.of(segment.bytes().length);
      case IdeStreamBinaryFrame.HttpEnd ignored -> OptionalInt.empty();
      case IdeStreamBinaryFrame.Ack ignored -> OptionalInt.empty();
    };
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
