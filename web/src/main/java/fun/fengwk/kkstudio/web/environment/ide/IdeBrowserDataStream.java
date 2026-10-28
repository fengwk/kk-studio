package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxBackpressureException;
import fun.fengwk.kkstudio.ide.mux.MuxClose;
import fun.fengwk.kkstudio.ide.mux.MuxDataKind;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.ide.mux.MuxReceivedData;
import fun.fengwk.kkstudio.ide.mux.MuxReset;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条浏览器 IDE WebSocket 会话到 mux 逻辑流的桥。
 *
 * <p>浏览器分段（TEXT/BINARY + FIN）保持边界与顺序：入站进入 512 KiB 有界队列形成背压；出站按 64 KiB 分段透传，文本用流式 UTF-8 解码器
 * 跨分段解码，绝不整包缓冲。只有浏览器真正发出该段后才归还 mux credit；浏览器写失败或关闭时 RESET 该逻辑流，不影响同一物理连接上的其它流。
 */
@Slf4j
final class IdeBrowserDataStream implements MuxStreamListener {

  private static final long MAX_INBOUND_BYTES = 512L * 1024L;

  private static final int MAX_INBOUND_FRAMES = 1024;

  private static final long MAX_INBOUND_BLOCK_MILLIS = 60_000L;

  private static final long WAIT_SLICE_MILLIS = 100L;

  private static final long BACKPRESSURE_RETRY_MILLIS = 5L;

  private record Frame(boolean text, byte[] payload, boolean last) {}

  private final WebSocketSession browser;
  private final EnvironmentId environmentId;
  private final UUID viewId;
  private final long bindingEpoch;
  private final String rawPathAndQuery;
  private final DaemonConnectionIdentity controlIdentity;
  private final List<MuxHeader> headers;
  private final IdeDataConnectionRegistry registry;
  private final Runnable onClosed;

  private final Object lock = new Object();
  private final Deque<Frame> inbound = new ArrayDeque<>();
  private final CharsetDecoder textDecoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  private long inboundBytes;
  private byte[] textPending = new byte[0];
  private boolean attached;
  private boolean headersReceived;
  private boolean closeReceived;
  private boolean closeSent;
  private MuxStream stream;
  private Thread writer;
  private final Deque<Runnable> pendingCallbacks = new ArrayDeque<>();

  IdeBrowserDataStream(
      WebSocketSession browser,
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String rawPathAndQuery,
      List<MuxHeader> headers,
      DaemonConnectionIdentity controlIdentity,
      IdeDataConnectionRegistry registry,
      Runnable onClosed) {
    this.browser = Objects.requireNonNull(browser, "browser");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.viewId = Objects.requireNonNull(viewId, "viewId");
    this.bindingEpoch = bindingEpoch;
    this.rawPathAndQuery = Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    this.controlIdentity = Objects.requireNonNull(controlIdentity, "controlIdentity");
    this.headers = List.copyOf(headers);
    this.registry = Objects.requireNonNull(registry, "registry");
    this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
  }

  void start() {
    writer = Thread.ofVirtual().name("ide-browser-ws-" + browser.getId()).start(this::run);
  }

  /** 容器线程入队一个浏览器分段；队列满时按字节预算阻塞形成 TCP 背压。 */
  boolean offer(boolean text, byte[] payload, boolean last) {
    Objects.requireNonNull(payload, "payload");
    if (stopped.get()) {
      return false;
    }
    long size = payload.length;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_INBOUND_BLOCK_MILLIS);
    synchronized (lock) {
      while (!stopped.get()
          && (inboundBytes + size > MAX_INBOUND_BYTES || inbound.size() >= MAX_INBOUND_FRAMES)) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return false;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(lock, remaining);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      if (stopped.get()) {
        return false;
      }
      inbound.addLast(new Frame(text, payload.clone(), last));
      inboundBytes += size;
      lock.notifyAll();
    }
    return true;
  }

  /** 浏览器已完成关闭握手；把关闭码转述给逻辑流，不重放未发送的浏览器数据。 */
  void browserClosed(CloseStatus status) {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    synchronized (lock) {
      inbound.clear();
      inboundBytes = 0L;
      lock.notifyAll();
    }
    MuxStream current;
    boolean ready;
    synchronized (lock) {
      current = stream;
      ready = headersReceived;
    }
    if (current != null && ready && !closeSent) {
      closeSent = true;
      Thread.ofVirtual()
          .name("ide-browser-ws-close-" + browser.getId())
          .start(() -> closeUpstream(current, status));
    } else if (current != null && !ready) {
      resetQuietly(current, "CLIENT_CLOSED", "browser websocket closed before upstream handshake");
    }
    finish();
  }

  void browserFailed(Throwable error) {
    if (stopped.compareAndSet(false, true)) {
      MuxStream current;
      synchronized (lock) {
        current = stream;
        inbound.clear();
        inboundBytes = 0L;
        lock.notifyAll();
      }
      if (current != null) {
        resetQuietly(current, "CLIENT_ABORTED", "browser websocket disconnected");
      }
      finish();
    }
  }

  private void run() {
    try {
      MuxStream opened =
          registry.openWebSocket(
              environmentId,
              viewId,
              bindingEpoch,
              IdeDataInternalPaths.upstreamPath(viewId, bindingEpoch, rawPathAndQuery),
              headers,
              controlIdentity,
              this);
      if (stopped.get()) {
        resetQuietly(opened, "CLIENT_CLOSED", "browser websocket closed before stream attach");
        return;
      }
      attach(opened);
      opened
          .closed()
          .whenComplete(
              (ignored, error) -> {
                if (!stopped.get()) {
                  closeBrowser(CloseStatus.SERVER_ERROR.withReason("IDE data stream ended"));
                }
              });
      while (!stopped.get()) {
        Frame frame;
        synchronized (lock) {
          while (!stopped.get() && (inbound.isEmpty() || !headersReceived)) {
            try {
              lock.wait(WAIT_SLICE_MILLIS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              return;
            }
          }
          if (stopped.get()) {
            return;
          }
          frame = inbound.removeFirst();
          inboundBytes -= frame.payload().length;
          lock.notifyAll();
        }
        if (!sendFrame(frame)) {
          return;
        }
      }
    } catch (RuntimeException error) {
      fail("UPSTREAM_UNAVAILABLE", "IDE data stream is unavailable", error);
      closeBrowser(CloseStatus.SERVER_ERROR.withReason("IDE data stream is unavailable"));
    }
  }

  private void attach(MuxStream opened) {
    List<Runnable> callbacks;
    synchronized (lock) {
      stream = opened;
      attached = true;
      callbacks = List.copyOf(pendingCallbacks);
      pendingCallbacks.clear();
    }
    callbacks.forEach(Runnable::run);
  }

  private void dispatch(Runnable callback) {
    synchronized (lock) {
      if (!attached) {
        pendingCallbacks.addLast(callback);
        return;
      }
    }
    callback.run();
  }

  private boolean sendFrame(Frame frame) {
    byte[] payload = frame.payload();
    if (payload.length == 0) {
      return sendChunk(frame.text(), frame.last(), payload);
    }
    int offset = 0;
    while (offset < payload.length && !stopped.get()) {
      int size = Math.min(MuxLimits.MAX_PAYLOAD_BYTES, payload.length - offset);
      if (frame.text()) {
        size = trimToUtf8Boundary(payload, offset, size);
      }
      boolean last = offset + size == payload.length && frame.last();
      if (!sendChunk(frame.text(), last, Arrays.copyOfRange(payload, offset, offset + size))) {
        return false;
      }
      offset += size;
    }
    return !stopped.get();
  }

  private boolean sendChunk(boolean text, boolean fin, byte[] payload) {
    while (!stopped.get()) {
      MuxStream current = stream;
      if (current == null) {
        return false;
      }
      try {
        CompletableFuture<Void> write = current.sendWsData(text, fin, payload);
        if (!awaitWrite(write)) {
          return false;
        }
        return true;
      } catch (MuxBackpressureException error) {
        try {
          Thread.sleep(BACKPRESSURE_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      } catch (RuntimeException error) {
        fail("UPSTREAM_FAILED", "IDE websocket stream failed", error);
        return false;
      }
    }
    return false;
  }

  private boolean awaitWrite(CompletableFuture<Void> write) {
    while (!stopped.get()) {
      try {
        write.get(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS);
        return true;
      } catch (TimeoutException ignored) {
        // 等待物理写或 daemon credit；继续检查关闭状态。
      } catch (ExecutionException error) {
        fail("UPSTREAM_FAILED", "IDE websocket stream failed", error.getCause());
        return false;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    write.cancel(true);
    return false;
  }

  @Override
  public void onHeaders(MuxStream stream, MuxHeaders headers) {
    dispatch(
        () -> {
          if (headers.status() != 101) {
            fail("PROTOCOL_ERROR", "WS upstream handshake status is invalid", null);
            return;
          }
          synchronized (lock) {
            headersReceived = true;
            lock.notifyAll();
          }
        });
  }

  @Override
  public void onData(MuxStream stream, MuxReceivedData data) {
    dispatch(
        () -> {
          if (stopped.get()) {
            return;
          }
          if (data.kind() == MuxDataKind.HTTP) {
            fail("PROTOCOL_ERROR", "HTTP DATA received on a websocket stream", null);
            return;
          }
          if (!sendToBrowser(data)) {
            return;
          }
          data.consumed();
        });
  }

  @Override
  public void onEnd(MuxStream stream) {
    dispatch(() -> fail("PROTOCOL_ERROR", "END received on a websocket stream", null));
  }

  @Override
  public void onReset(MuxStream stream, MuxReset reset) {
    dispatch(
        () -> {
          stopped.set(true);
          synchronized (lock) {
            inbound.clear();
            inboundBytes = 0L;
            lock.notifyAll();
          }
          closeBrowser(CloseStatus.SERVER_ERROR.withReason(safeReason(reset.message())));
          finish();
        });
  }

  @Override
  public void onClose(MuxStream stream, MuxClose close) {
    dispatch(
        () -> {
          closeReceived = true;
          stopped.set(true);
          synchronized (lock) {
            inbound.clear();
            inboundBytes = 0L;
            lock.notifyAll();
          }
          closeBrowser(closeStatus(close));
          finish();
        });
  }

  private boolean sendToBrowser(MuxReceivedData data) {
    if (data.kind() == MuxDataKind.WS_BINARY) {
      return sendBrowserBinary(data.payload(), data.fin());
    }
    return sendBrowserText(data.payload(), data.fin());
  }

  private boolean sendBrowserBinary(byte[] payload, boolean last) {
    Session nativeSession = nativeSession();
    synchronized (browser) {
      try {
        if (nativeSession != null && nativeSession.isOpen()) {
          nativeSession.getBasicRemote().sendBinary(ByteBuffer.wrap(payload), last);
        } else {
          browser.sendMessage(new BinaryMessage(ByteBuffer.wrap(payload), last));
        }
        return true;
      } catch (IOException | RuntimeException error) {
        fail("BROWSER_SEND_FAILED", "browser websocket write failed", error);
        return false;
      }
    }
  }

  /** 文本分段按流式 UTF-8 解码；消息结束仍有未完成序列时按非法编码关闭该流。 */
  private boolean sendBrowserText(byte[] bytes, boolean last) {
    ByteBuffer input = ByteBuffer.allocate(textPending.length + bytes.length);
    input.put(textPending);
    input.put(bytes);
    input.flip();
    CharBuffer chars = CharBuffer.allocate(input.remaining() + 1);
    try {
      var result = textDecoder.decode(input, chars, last);
      if (result.isError()) {
        result.throwException();
      }
      if (last && input.hasRemaining()) {
        fail("INVALID_UTF8", "invalid utf-8 in websocket text", null);
        return false;
      }
      textPending = new byte[input.remaining()];
      input.get(textPending);
      chars.flip();
      String text = chars.toString();
      return sendBrowserTextPart(text, last && textPending.length == 0);
    } catch (CharacterCodingException error) {
      fail("INVALID_UTF8", "invalid utf-8 in websocket text", error);
      return false;
    }
  }

  private boolean sendBrowserTextPart(String text, boolean last) {
    Session nativeSession = nativeSession();
    synchronized (browser) {
      try {
        if (nativeSession != null && nativeSession.isOpen()) {
          RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
          remote.sendText(text, last);
        } else {
          browser.sendMessage(new TextMessage(text, last));
        }
        return true;
      } catch (IOException | RuntimeException error) {
        fail("BROWSER_SEND_FAILED", "browser websocket write failed", error);
        return false;
      }
    }
  }

  private void closeUpstream(MuxStream current, CloseStatus status) {
    try {
      int code = wireCloseCode(status.getCode());
      String reason = safeReason(status.getReason());
      current.sendClose(code, reason).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      resetQuietly(current, "CLIENT_ABORTED", "browser websocket close interrupted");
    } catch (ExecutionException | TimeoutException error) {
      resetQuietly(current, "CLIENT_ABORTED", "browser websocket closed");
    } catch (RuntimeException error) {
      resetQuietly(current, "CLIENT_ABORTED", "browser websocket closed");
    }
  }

  private Session nativeSession() {
    if (browser instanceof NativeWebSocketSession nativeSession) {
      return nativeSession.getNativeSession(Session.class);
    }
    return null;
  }

  private void fail(String code, String message, Throwable cause) {
    if (failure.compareAndSet(null, cause == null ? new IOException(message) : cause)) {
      MuxStream current;
      synchronized (lock) {
        current = stream;
        stopped.set(true);
        inbound.clear();
        inboundBytes = 0L;
        lock.notifyAll();
      }
      if (current != null) {
        resetQuietly(current, code, message);
      }
      closeBrowser(CloseStatus.SERVER_ERROR.withReason(safeReason(message)));
      finish();
    }
  }

  private void resetQuietly(MuxStream current, String code, String message) {
    if (!current.isOpen()) {
      return;
    }
    try {
      current.reset(code, message);
    } catch (RuntimeException ignored) {
      // 流/连接已由其它路径收敛；不重试。
    }
  }

  private void finish() {
    onClosed.run();
  }

  private void closeBrowser(CloseStatus status) {
    synchronized (browser) {
      if (!browser.isOpen()) {
        return;
      }
      try {
        browser.close(status);
      } catch (IOException ignored) {
        // 浏览器已断开。
      }
    }
  }

  private static CloseStatus closeStatus(MuxClose close) {
    int code = wireCloseCode(close.code());
    return new CloseStatus(code, safeReason(close.reason()));
  }

  private static int wireCloseCode(int code) {
    if (code >= 3000 && code <= 4999) {
      return code;
    }
    if (code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006) {
      return code;
    }
    return 1000;
  }

  /** 关闭原因上限 123 UTF-8 字节；截断到合法边界。 */
  private static String safeReason(String reason) {
    if (reason == null) {
      return "";
    }
    byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MuxLimits.MAX_CLOSE_REASON_BYTES) {
      return reason;
    }
    int end = MuxLimits.MAX_CLOSE_REASON_BYTES;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
      end--;
    }
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  /** 把浏览器文本分段边界调整到 UTF-8 码点边界；返回 0 表示当前 chunk 无法切分。 */
  private static int trimToUtf8Boundary(byte[] payload, int offset, int size) {
    if (size <= 0) {
      return size;
    }
    int end = offset + size;
    if (end >= payload.length) {
      return size;
    }
    int probe = end - 1;
    while (probe >= offset && probe > end - 4 && (payload[probe] & 0xC0) == 0x80) {
      probe--;
    }
    if (probe < offset) {
      return size;
    }
    int leading = payload[probe] & 0xFF;
    int expected =
        leading < 0x80 ? 1 : leading >= 0xF0 ? 4 : leading >= 0xE0 ? 3 : leading >= 0xC0 ? 2 : 1;
    int consumed = end - probe;
    return consumed < expected ? probe - offset : size;
  }
}
