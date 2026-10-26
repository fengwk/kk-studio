package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeHeader;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamControlFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingException;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.IdeStreamRelay;
import fun.fengwk.kkstudio.ide.stream.IdeStreamTicketService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条 IDE 浏览器 WS 会话（真实浏览器或内部单跳端点）到 Daemon 反向连接的分段转发状态机。
 *
 * <p>入站分段进入按字节有界的队列（阻塞容器线程形成 TCP 背压，超时 fail-closed），出站按 credit 分段发送：二进制逐段透传，文本用流式 UTF-8
 * 解码器跨分段解码并在码点边界切分，绝不整包缓冲、绝不先 ACK 后交付。转发线程是虚拟线程，活跃 WS 不设整体寿命，仅 metadata 就绪 阶段有
 * deadline。控制连接短暂断开不影响已建立的流；另一个 Daemon 实例接管时，代际清理关闭反向流，本流随之结束。
 *
 * <p>Daemon 侧关闭时把它给出的关闭码（code-server 的关闭码或协议原因）原样转述给浏览器；每条流结束时写一行诊断日志（结束原因、双方关闭码、Daemon
 * 失败回执、双向帧数与字节数、时长），使线上可以判定关闭方。
 */
@Slf4j
final class IdeBrowserStreamSession {

  /** 单次分段上限：与 wire 载荷上限一致。 */
  static final int SEGMENT_BYTES = DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES;

  /** 入站队列的字节预算；超出即阻塞容器线程形成背压。 */
  private static final long MAX_INBOUND_BYTES = 512L * 1024L;

  /** 零载荷分段同样占据队列预算。 */
  static final int MAX_INBOUND_FRAMES = 1024;

  /** 容器线程最长阻塞时间；超过即 fail-closed，避免长期占用容器线程。 */
  private static final long MAX_INBOUND_BLOCK_MILLIS = 60_000L;

  private static final long POLL_TICK_MILLIS = 5L;

  record Frame(boolean text, byte[] payload, boolean last) {}

  /** 一条浏览器 WS 会话的转发状态机：单线程内推进「浏览器→Daemon」与「Daemon→浏览器」两个方向。 */
  private final WebSocketSession browser;

  private final EnvironmentId environmentId;
  private final IdeBrowserSessionRegistry.Session session;
  private final String rawPathAndQuery;
  private final List<IdeHeader> headers;
  private final Deque<Frame> inbound = new ArrayDeque<>();
  private final Object inboundLock = new Object();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final CharsetDecoder textDecoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
  private long inboundBytes;

  private final IdeStreamDeps deps;
  private final Runnable onClosed;
  private volatile IdeStreamPeer peer;
  private IdeStreamRelay relay;

  /** 流式解码器尚未消费的 ≤3 字节不完整 UTF-8 序列，必须跨分段保留。 */
  private byte[] textPending = new byte[0];

  /** 本流的结束原因；只记录首个原因，浏览器主动断开为默认值。 */
  private String outcome;

  private CloseStatus browserClose;
  private long fromDaemonFrames;
  private long fromDaemonBytes;
  private long toDaemonFrames;
  private long toDaemonBytes;

  IdeBrowserStreamSession(
      WebSocketSession browser,
      EnvironmentId environmentId,
      IdeBrowserSessionRegistry.Session session,
      String rawPathAndQuery,
      List<IdeHeader> headers,
      IdeStreamDeps deps,
      Runnable onClosed) {
    this.browser = browser;
    this.environmentId = environmentId;
    this.session = session;
    this.rawPathAndQuery = rawPathAndQuery;
    this.headers = headers;
    this.deps = deps;
    this.onClosed = onClosed;
  }

  void start() {
    Thread.ofVirtual().name("ide-browser-stream-" + browser.getId()).start(this::run);
  }

  /**
   * 容器线程入队一个浏览器分段；队列按字节预算有界，满时阻塞形成 TCP 背压。
   *
   * @return false 表示连接已关闭或长时间无法推进（调用方必须 fail-closed）
   */
  boolean offer(Frame frame) {
    long size = frame.payload().length;
    long deadline = System.currentTimeMillis() + MAX_INBOUND_BLOCK_MILLIS;
    synchronized (inboundLock) {
      while (!stopped.get()
          && (inboundBytes + size > MAX_INBOUND_BYTES || inbound.size() >= MAX_INBOUND_FRAMES)) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
          return false;
        }
        try {
          inboundLock.wait(Math.min(remaining, 1000L));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      if (stopped.get()) {
        return false;
      }
      inbound.addLast(frame);
      inboundBytes += size;
      inboundLock.notifyAll();
      return true;
    }
  }

  void onBrowserDetached() {
    stopped.set(true);
    synchronized (inboundLock) {
      inboundLock.notifyAll();
    }
    IdeStreamPeer current = peer;
    if (current != null) {
      current.close();
    }
  }

  private void run() {
    long startedNanos = System.nanoTime();
    IdeRequest request = null;
    IdeStreamTicketService.Issued issued =
        deps.streamTickets().issue(session.identity(), IdeStreamMode.WS);
    AtomicReference<UUID> invocation = new AtomicReference<>();
    AtomicBoolean abandoned = new AtomicBoolean();
    Runnable abandonOnce =
        () -> {
          UUID id = invocation.get();
          if (id != null && abandoned.compareAndSet(false, true)) {
            deps.transport().abandonIde(id);
          }
        };
    IdeStreamPairingRegistry<IdeStreamPeer>.Pairing pairing =
        deps.pairings()
            .open(
                issued.ticket(),
                session.identity(),
                deps.properties().requirePairingTimeout(),
                abandonOnce);
    try {
      IdeCommand command =
          new IdeCommand(
              IdeAction.OPEN_STREAM,
              null,
              null,
              issued.ticket(),
              deps.properties().streamUrl(environmentId, issued.ticket()),
              IdeStreamMode.WS,
              "GET",
              rawPathAndQuery,
              headers);
      request = deps.transport().requestIde(environmentId, command);
      invocation.set(request.invocationId());
      peer = pairing.awaitPeer();
      relay =
          new IdeStreamRelay(
              new IdeStreamRelay.Sink() {
                @Override
                public void sendText(String frame) {
                  peer.sendText(frame);
                }

                @Override
                public void sendBinary(byte[] frame) {
                  peer.sendBinary(frame);
                }

                @Override
                public void sendAcknowledgement(byte[] encodedAck) {
                  peer.sendAcknowledgement(encodedAck);
                }
              });
      if (awaitReady(System.nanoTime() + deps.properties().requireRequestTimeout().toNanos())) {
        relayUntilClosed();
      } else {
        end("NOT_READY", CloseStatus.SERVER_ERROR);
      }
    } catch (IdeStreamPairingException error) {
      end("PAIRING_FAILED", CloseStatus.SERVER_ERROR);
    } catch (EnvironmentCapabilityUnavailableException
        | EnvironmentCapabilityBusyException
        | EnvironmentCapabilitySendUncertainException error) {
      end("DAEMON_UNAVAILABLE", CloseStatus.SERVER_ERROR);
    } catch (RuntimeException error) {
      end("FAILED " + error.getClass().getSimpleName(), CloseStatus.SERVER_ERROR);
    } finally {
      IdeStreamPeer current = peer;
      CloseStatus daemonClose = current == null ? null : current.remoteCloseStatus();
      // Daemon 侧异常结束时，其 OPEN_STREAM 回执携带失败原因且紧随关闭到达：短暂等待后再放弃该调用。
      boolean daemonSideFailure =
          outcome != null
              && (daemonClose == null || daemonClose.getCode() != CloseStatus.NORMAL.getCode());
      String daemonReply =
          IdeStreamDiagnostics.replyFailure(
              request, daemonSideFailure ? IdeStreamDiagnostics.REPLY_GRACE : Duration.ZERO);
      abandonOnce.run();
      pairing.close();
      if (current != null) {
        current.close();
      }
      onClosed.run();
      log.info(
          "ide websocket stream ended: environment={} outcome={} daemonClose={} browserClose={}"
              + " daemonReply={} fromDaemon={} frames/{} bytes toDaemon={} frames/{} bytes"
              + " durationMs={}",
          environmentId,
          outcome == null ? "BROWSER_CLOSED" : outcome,
          IdeStreamDiagnostics.describe(daemonClose),
          IdeStreamDiagnostics.describe(browserClose),
          daemonReply,
          fromDaemonFrames,
          fromDaemonBytes,
          toDaemonFrames,
          toDaemonBytes,
          Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }
  }

  /** 记录首个结束原因并以给定状态关闭浏览器连接。 */
  private void end(String reason, CloseStatus status) {
    if (outcome == null) {
      outcome = reason;
    }
    closeBrowser(status);
  }

  /** 等待 Daemon 的 {@code WS_READY}；此前的任何数据帧都是协议错误。只在 metadata 就绪阶段有 deadline。 */
  private boolean awaitReady(long readyDeadlineNanos) {
    while (!stopped.get() && System.nanoTime() < readyDeadlineNanos) {
      IdeStreamPeer.Inbound frame = pollPeer(readyDeadlineNanos, POLL_TICK_MILLIS);
      if (frame == null) {
        if (peer.closed()) {
          return false;
        }
        continue;
      }
      if (frame instanceof IdeStreamPeer.Inbound.Text text) {
        IdeStreamControlFrame control = relay.receiveText(text.json());
        return control instanceof IdeStreamControlFrame.WsReady;
      }
      return false;
    }
    return false;
  }

  /** 双方向转发主循环：活跃 WS 不设整体寿命，直到浏览器或 Daemon 一侧断开。 */
  private void relayUntilClosed() {
    Pending pending = null;
    while (!stopped.get()) {
      if (pending == null) {
        pending = pollInbound();
      }
      if (pending != null) {
        pending = flush(pending);
      }
      IdeStreamPeer.Inbound frame =
          pollPeer(Long.MAX_VALUE, pending == null ? POLL_TICK_MILLIS : 0L);
      if (frame != null) {
        if (!handleDaemonFrame(frame)) {
          end(outcome, CloseStatus.SERVER_ERROR);
          return;
        }
        continue;
      }
      if (peer.closed()) {
        // 原样转述 Daemon 给出的关闭码（code-server 关闭码或协议原因），缺失时才统一为 1011。
        end("DAEMON_CLOSED", IdeStreamPeer.relayable(peer.remoteCloseStatus()));
        return;
      }
    }
    closeBrowser(CloseStatus.NORMAL);
  }

  /** 处理一个 Daemon 入站帧；返回 false 表示需要 fail-closed，此时已记录结束原因。 */
  private boolean handleDaemonFrame(IdeStreamPeer.Inbound frame) {
    if (frame instanceof IdeStreamPeer.Inbound.Text text) {
      IdeStreamControlFrame control = relay.receiveText(text.json());
      if (control instanceof IdeStreamControlFrame.Error) {
        outcome = "DAEMON_ERROR";
        return false;
      }
      return true;
    }
    IdeStreamBinaryFrame decoded =
        relay.receiveBinary(((IdeStreamPeer.Inbound.Binary) frame).frame());
    switch (decoded) {
      case IdeStreamBinaryFrame.WsSegment segment -> {
        fromDaemonFrames++;
        fromDaemonBytes += segment.bytes().length;
        boolean delivered = segment.text() ? sendTextPart(segment) : sendBinaryPart(segment);
        if (!delivered) {
          if (outcome == null) {
            outcome = "BROWSER_SEND_FAILED";
          }
          return false;
        }
        relay.acknowledge(segment);
        return true;
      }
      case IdeStreamBinaryFrame.Ack ignored -> {
        // 仅用于释放发送 credit。
        return true;
      }
      case IdeStreamBinaryFrame.HttpBody ignored -> {
        outcome = "PROTOCOL_ERROR http body on a websocket stream";
        return false;
      }
      case IdeStreamBinaryFrame.HttpEnd ignored -> {
        outcome = "PROTOCOL_ERROR http end on a websocket stream";
        return false;
      }
    }
  }

  /**
   * 文本分段：用流式 UTF-8 解码器跨分段解码，保留 ≤3 字节不完整序列到下一段；非法编码即 1007 关闭。
   *
   * <p>每个 Daemon 文本分段都是完整消息的片段，因此最后一段结束时不得残留不完整序列。
   */
  private boolean sendTextPart(IdeStreamBinaryFrame.WsSegment segment) {
    byte[] bytes = segment.bytes();
    ByteBuffer input = ByteBuffer.allocate(textPending.length + bytes.length);
    input.put(textPending);
    input.put(bytes);
    input.flip();
    CharBuffer chars = CharBuffer.allocate(input.remaining() + 1);
    try {
      CoderResult result = textDecoder.decode(input, chars, segment.last());
      if (result.isError()) {
        result.throwException();
      }
      if (segment.last() && input.hasRemaining()) {
        // 消息结束仍有不完整序列：非法 UTF-8。
        end("INVALID_UTF8", CloseStatus.BAD_DATA.withReason("invalid utf-8"));
        return false;
      }
      textPending = new byte[input.remaining()];
      input.get(textPending);
      chars.flip();
      return sendBrowserText(chars.toString(), segment.last() && textPending.length == 0);
    } catch (CharacterCodingException error) {
      end("INVALID_UTF8", CloseStatus.BAD_DATA.withReason("invalid utf-8"));
      return false;
    }
  }

  /** 用原生 basicRemote 分段发送文本分段（内容已由流式解码器产出合法字符）。 */
  private boolean sendBrowserText(String text, boolean last) {
    if (text.isEmpty() && !last) {
      return true;
    }
    Session nativeSession = nativeSession();
    if (nativeSession == null || !nativeSession.isOpen()) {
      return false;
    }
    RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
    synchronized (browser) {
      try {
        remote.sendText(text, last);
        return true;
      } catch (IOException | RuntimeException error) {
        if (outcome == null) {
          outcome = "BROWSER_SEND_FAILED " + error.getClass().getSimpleName();
        }
        return false;
      }
    }
  }

  /** 用原生 basicRemote 分段发送二进制分段：最后一段带 FIN，其余为 partial（逐段透传，不整包缓冲）。 */
  private boolean sendBinaryPart(IdeStreamBinaryFrame.WsSegment segment) {
    byte[] bytes = segment.bytes();
    if (bytes.length == 0 && !segment.last()) {
      return true;
    }
    Session nativeSession = nativeSession();
    if (nativeSession == null || !nativeSession.isOpen()) {
      return false;
    }
    RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
    synchronized (browser) {
      try {
        remote.sendBinary(ByteBuffer.wrap(bytes), segment.last());
        return true;
      } catch (IOException | RuntimeException error) {
        if (outcome == null) {
          outcome = "BROWSER_SEND_FAILED " + error.getClass().getSimpleName();
        }
        return false;
      }
    }
  }

  /** 把一条浏览器分段按 credit 逐段发给 Daemon；返回仍未发完的剩余部分。 */
  private Pending flush(Pending pending) {
    byte[] payload = pending.frame.payload();
    int offset = pending.offset;
    boolean messageEnd = pending.frame.last();
    if (payload.length == 0) {
      // 零长度分段同样按最小帧代价消耗 credit。
      if (!relay.canSendToDaemon(0)) {
        return pending;
      }
      relay.sendWsSegment(pending.frame.text(), messageEnd, new byte[0]);
      toDaemonFrames++;
      return null;
    }
    while (offset < payload.length) {
      int maxPayload = relay.maxPayloadSendableToDaemon();
      if (maxPayload <= 0) {
        return new Pending(pending.frame, offset);
      }
      int size = Math.min(payload.length - offset, maxPayload);
      size = trimToCodepointBoundary(pending.frame.text(), payload, offset, size);
      if (size <= 0) {
        // 当前 credit 只够切在码点中间：等待 Daemon ACK 释放更多 credit，绝不发出非法 UTF-8。
        return new Pending(pending.frame, offset);
      }
      byte[] chunk = Arrays.copyOfRange(payload, offset, offset + size);
      offset += size;
      relay.sendWsSegment(pending.frame.text(), messageEnd && offset >= payload.length, chunk);
      toDaemonFrames++;
      toDaemonBytes += size;
    }
    return null;
  }

  /**
   * 文本分段必须在码点边界切分，保证每个发出到 Daemon 的文本分段自身是合法 UTF-8。
   *
   * <p>只需回退到最近一个完整码点的边界：末尾连续多字节序列至多 3 个续字节。
   */
  private static int trimToCodepointBoundary(boolean text, byte[] payload, int offset, int size) {
    if (!text || offset + size >= payload.length) {
      return size;
    }
    int end = offset + size;
    int continuations = 0;
    while (continuations < 3
        && end - continuations - 1 >= offset
        && (payload[end - continuations - 1] & 0xC0) == 0x80) {
      continuations++;
    }
    int lead = payload[end - continuations - 1] & 0xFF;
    int sequenceLength = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
    int available = continuations + 1;
    if (sequenceLength > available) {
      // 只发出完整码点；可能为 0，此时由调用方等待更多 credit 再切。
      return size - available;
    }
    return size;
  }

  private Pending pollInbound() {
    synchronized (inboundLock) {
      Frame frame = inbound.pollFirst();
      if (frame == null) {
        return null;
      }
      inboundBytes -= frame.payload().length;
      inboundLock.notifyAll();
      return new Pending(frame, 0);
    }
  }

  private IdeStreamPeer.Inbound pollPeer(long deadlineNanos, long waitMillis) {
    long wait = waitMillis;
    if (deadlineNanos != Long.MAX_VALUE) {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0L) {
        throw new IdeStreamPairingException("ide websocket exchange timed out");
      }
      wait = Math.min(waitMillis, Math.max(1L, remaining / 1_000_000L));
    }
    try {
      return peer.poll(Duration.ofMillis(wait));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IdeStreamPairingException("ide websocket exchange was interrupted");
    }
  }

  private Session nativeSession() {
    if (browser instanceof NativeWebSocketSession nativeWebSocket) {
      Object session = nativeWebSocket.getNativeSession();
      if (session instanceof Session jakartaSession) {
        return jakartaSession;
      }
    }
    return null;
  }

  private void closeBrowser(CloseStatus status) {
    stopped.set(true);
    synchronized (browser) {
      if (browser.isOpen()) {
        if (browserClose == null) {
          browserClose = status;
        }
        try {
          browser.close(status);
        } catch (IOException ignored) {
          // 浏览器侧已断开。
        }
      }
    }
    synchronized (inboundLock) {
      inboundLock.notifyAll();
    }
  }

  record Pending(Frame frame, int offset) {}
}
