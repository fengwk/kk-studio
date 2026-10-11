package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeHeader;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamControlFrame;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamMode;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingException;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamRelay;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;

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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IDE 独立 Origin 上浏览器原生 WebSocket 的数据面：把浏览器 WS 与 Daemon 反向连接配对后双向转发。
 *
 * <p>全程使用**原生分段**而非整条消息缓冲：浏览器侧 handler 声明支持 partial message，收发都按 ≤64 KiB 分段与 FIN 标记；Daemon 侧的分段按
 * FIN 直接映射为浏览器侧的分段（二进制逐帧透传，文本用流式 UTF-8 解码器跨分段解码，保留 ≤3 字节不完整序列），因此大文件/长连接 编辑不会造成整包内存峰值，也不会「先 ACK
 * 后缓冲」——只在字节真正交给浏览器后才 ACK。
 *
 * <p>浏览器→Daemon 方向的入站队列按字节预算有界，超界时容器线程阻塞（TCP 背压）而不是无界缓冲；长时间无法推进即 fail-closed。转发线程使用虚拟线程， 且活跃 WS
 * 不设整体寿命：只在 metadata 就绪阶段有 deadline，之后一直转发到浏览器断开、Daemon 断开或代际变更。
 */
@Slf4j
@Component
public final class IdeBrowserWebSocketGateway {

  /** 单次分段上限：与 wire 载荷上限一致。 */
  private static final int SEGMENT_BYTES = DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES;

  /** 浏览器→Daemon 入站队列的字节预算；超出即阻塞容器线程形成背压。 */
  private static final long MAX_INBOUND_BYTES = 512L * 1024L;

  /** 容器线程最长阻塞时间；超过即 fail-closed，避免长期占用容器线程。 */
  private static final long MAX_INBOUND_BLOCK_MILLIS = 60_000L;

  private static final long POLL_TICK_MILLIS = 5L;

  /** 代际/租约复核周期：有限周期检查，绝不逐帧查库。 */
  private static final long GENERATION_RECHECK_MILLIS = 30_000L;

  private final IdeCommandTransport transport;
  private final IdeStreamTicketService streamTickets;
  private final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
  private final IdeGatewayProperties properties;
  private final IdeBrowserSessionRegistry sessions;
  private final Map<String, BrowserStreamSession> active = new ConcurrentHashMap<>();

  private volatile DefaultHandshakeHandler handshakeHandler;

  public IdeBrowserWebSocketGateway(
      IdeCommandTransport transport,
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeGatewayProperties properties,
      IdeBrowserSessionRegistry sessions) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  /** 该请求是否为浏览器 WebSocket 升级。 */
  public static boolean isWebSocketUpgrade(HttpServletRequest request) {
    String upgrade = request.getHeader("Upgrade");
    if (upgrade == null || !upgrade.trim().equalsIgnoreCase("websocket")) {
      return false;
    }
    String connection = request.getHeader("Connection");
    if (connection == null) {
      return false;
    }
    return Arrays.stream(connection.split(","))
        .anyMatch(token -> token.trim().equalsIgnoreCase("upgrade"));
  }

  /**
   * 处理一个 IDE Origin 上的浏览器 WS 升级：先做精确同源、cookie 与代际校验，再执行容器级握手。
   *
   * <p>校验失败直接以 HTTP 状态码拒绝，不建立任何反向连接。
   */
  public void handleUpgrade(
      EnvironmentId environmentId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    if (!properties.configured()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!IdeOriginPolicy.webSocketHandshakeAllowed(request)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    IdeBrowserSessionRegistry.Session session = requireSession(environmentId, request);
    if (session == null) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!generationCurrent(environmentId, session)) {
      response.sendError(HttpServletResponse.SC_CONFLICT);
      return;
    }
    List<IdeHeader> headers = IdeGatewayDataPlane.forwardRequestHeaders(request);
    AbstractWebSocketHandler handler =
        new BrowserHandler(environmentId, session, rawPathAndQuery, headers);
    WebSocketHttpRequestHandler upgrade =
        new WebSocketHttpRequestHandler(handler, handshakeHandler(request.getServletContext()));
    upgrade.handleRequest(request, response);
  }

  private DefaultHandshakeHandler handshakeHandler(ServletContext servletContext) {
    DefaultHandshakeHandler handler = handshakeHandler;
    if (handler == null) {
      synchronized (this) {
        handler = handshakeHandler;
        if (handler == null) {
          handler = new DefaultHandshakeHandler();
          handler.setServletContext(servletContext);
          handler.start();
          handshakeHandler = handler;
        }
      }
    }
    return handler;
  }

  private IdeBrowserSessionRegistry.Session requireSession(
      EnvironmentId environmentId, HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    String name = properties.requireSessionCookieName();
    for (Cookie cookie : cookies) {
      if (!name.equals(cookie.getName())) {
        continue;
      }
      Optional<IdeBrowserSessionRegistry.Session> session = sessions.lookup(cookie.getValue());
      if (session.isPresent() && session.get().environmentId().equals(environmentId)) {
        return session.get();
      }
    }
    return null;
  }

  private boolean generationCurrent(
      EnvironmentId environmentId, IdeBrowserSessionRegistry.Session session) {
    Optional<IdeSessionIdentity> current = transport.ideSessionIdentity(environmentId);
    return current.isPresent() && current.get().equals(session.identity());
  }

  /** 直接关闭浏览器连接（原始句柄，用于握手后无会话的 fail-closed 场景）。 */
  private static void closeBrowser(WebSocketSession browser, CloseStatus status) {
    synchronized (browser) {
      if (browser.isOpen()) {
        try {
          browser.close(status);
        } catch (IOException ignored) {
          // 浏览器侧已断开。
        }
      }
    }
  }

  /** 浏览器连接侧的窄句柄：入站分段进入按字节有界的队列，出站分段用原生 basicRemote 分段发送。 */
  private final class BrowserHandler extends AbstractWebSocketHandler {

    private final EnvironmentId environmentId;
    private final IdeBrowserSessionRegistry.Session session;
    private final String rawPathAndQuery;
    private final List<IdeHeader> headers;

    private BrowserHandler(
        EnvironmentId environmentId,
        IdeBrowserSessionRegistry.Session session,
        String rawPathAndQuery,
        List<IdeHeader> headers) {
      this.environmentId = environmentId;
      this.session = session;
      this.rawPathAndQuery = rawPathAndQuery;
      this.headers = headers;
    }

    @Override
    public boolean supportsPartialMessages() {
      return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browser) {
      // 分段交付：单段上限即分段大小，容器按段交付而不是整包缓冲。
      browser.setTextMessageSizeLimit(SEGMENT_BYTES);
      browser.setBinaryMessageSizeLimit(SEGMENT_BYTES);
      BrowserStreamSession stream =
          new BrowserStreamSession(browser, environmentId, session, rawPathAndQuery, headers);
      active.put(browser.getId(), stream);
      stream.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
      offer(
          browser,
          new Frame(true, message.getPayload().getBytes(StandardCharsets.UTF_8), message.isLast()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
      ByteBuffer payload = message.getPayload();
      byte[] bytes = new byte[payload.remaining()];
      payload.get(bytes);
      offer(browser, new Frame(false, bytes, message.isLast()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
      detach(browser, false);
    }

    @Override
    public void handleTransportError(WebSocketSession browser, Throwable exception) {
      detach(browser, true);
    }

    private void offer(WebSocketSession browser, Frame frame) {
      BrowserStreamSession stream = active.get(browser.getId());
      if (stream != null && !stream.offer(frame)) {
        // 有界入站预算/关闭：fail-closed 关闭浏览器连接，绝不无界缓冲。
        closeBrowser(browser, CloseStatus.POLICY_VIOLATION);
      }
    }

    private void detach(WebSocketSession browser, boolean error) {
      BrowserStreamSession stream = active.remove(browser.getId());
      if (stream != null) {
        stream.onBrowserDetached();
      }
      if (error) {
        closeBrowser(browser, CloseStatus.SERVER_ERROR);
      }
    }
  }

  /** 一个浏览器分段：{@code payload} 是分段字节（文本已按 UTF-8 编码），{@code last} 表示本条消息的最后一段。 */
  private record Frame(boolean text, byte[] payload, boolean last) {}

  /** 一条浏览器 WS 会话的转发状态机：单线程内推进「浏览器→Daemon」与「Daemon→浏览器」两个方向。 */
  private final class BrowserStreamSession {

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

    private volatile IdeStreamPeer peer;
    private IdeStreamRelay relay;

    /** 流式解码器尚未消费的 ≤3 字节不完整 UTF-8 序列，必须跨分段保留。 */
    private byte[] textPending = new byte[0];

    BrowserStreamSession(
        WebSocketSession browser,
        EnvironmentId environmentId,
        IdeBrowserSessionRegistry.Session session,
        String rawPathAndQuery,
        List<IdeHeader> headers) {
      this.browser = browser;
      this.environmentId = environmentId;
      this.session = session;
      this.rawPathAndQuery = rawPathAndQuery;
      this.headers = headers;
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
        while (!stopped.get() && inboundBytes + size > MAX_INBOUND_BYTES) {
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
      IdeStreamTicketService.Issued issued =
          streamTickets.issue(session.identity(), IdeStreamMode.WS);
      AtomicReference<UUID> invocation = new AtomicReference<>();
      AtomicBoolean abandoned = new AtomicBoolean();
      Runnable abandonOnce =
          () -> {
            UUID id = invocation.get();
            if (id != null && abandoned.compareAndSet(false, true)) {
              transport.abandonIde(id);
            }
          };
      IdeStreamPairingRegistry<IdeStreamPeer>.Pairing pairing =
          pairings.open(
              issued.ticket(),
              environmentId,
              session.identity().leaseToken(),
              properties.requirePairingTimeout(),
              abandonOnce);
      try {
        IdeCommand command =
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                null,
                null,
                issued.ticket(),
                properties.streamUrl(environmentId, issued.ticket()),
                IdeStreamMode.WS,
                "GET",
                rawPathAndQuery,
                headers);
        IdeRequest request = transport.requestIde(environmentId, command);
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
                });
        if (awaitReady(System.nanoTime() + properties.requireRequestTimeout().toNanos())) {
          relayUntilClosed();
        } else {
          closeBrowser(CloseStatus.SERVER_ERROR);
        }
      } catch (IdeStreamPairingException error) {
        closeBrowser(CloseStatus.SERVER_ERROR);
      } catch (EnvironmentCapabilityUnavailableException
          | EnvironmentCapabilityBusyException
          | EnvironmentCapabilitySendUncertainException error) {
        closeBrowser(CloseStatus.SERVER_ERROR);
      } catch (RuntimeException error) {
        log.debug("browser ide stream failed: {}", error.getMessage());
        closeBrowser(CloseStatus.SERVER_ERROR);
      } finally {
        abandonOnce.run();
        pairing.close();
        IdeStreamPeer current = peer;
        if (current != null) {
          current.close();
        }
        active.remove(browser.getId());
      }
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

    /** 双方向转发主循环：活跃 WS 不设整体寿命，直到浏览器/Daemon 断开或代际变更。 */
    private void relayUntilClosed() {
      Pending pending = null;
      long nextGenerationCheck = System.currentTimeMillis() + GENERATION_RECHECK_MILLIS;
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
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
          continue;
        }
        if (peer.closed()) {
          closeBrowser(CloseStatus.SERVER_ERROR);
          return;
        }
        long now = System.currentTimeMillis();
        if (now >= nextGenerationCheck) {
          nextGenerationCheck = now + GENERATION_RECHECK_MILLIS;
          if (!generationCurrent(environmentId, session)) {
            // 租约失效/接管：以有限周期复核并真实关闭本流。
            closeBrowser(CloseStatus.POLICY_VIOLATION);
            return;
          }
        }
      }
      closeBrowser(CloseStatus.NORMAL);
    }

    /** 处理一个 Daemon 入站帧；返回 false 表示协议错误需 fail-closed。 */
    private boolean handleDaemonFrame(IdeStreamPeer.Inbound frame) {
      if (frame instanceof IdeStreamPeer.Inbound.Text text) {
        IdeStreamControlFrame control = relay.receiveText(text.json());
        return !(control instanceof IdeStreamControlFrame.Error);
      }
      IdeStreamBinaryFrame decoded =
          relay.receiveBinary(((IdeStreamPeer.Inbound.Binary) frame).frame());
      switch (decoded) {
        case IdeStreamBinaryFrame.WsSegment segment -> {
          boolean delivered = segment.text() ? sendTextPart(segment) : sendBinaryPart(segment);
          if (!delivered) {
            return false;
          }
          relay.ackFromDaemon(segment.bytes().length);
          return true;
        }
        case IdeStreamBinaryFrame.Ack ignored -> {
          // 仅用于释放发送 credit。
          return true;
        }
        case IdeStreamBinaryFrame.HttpBody ignored -> {
          return false;
        }
        case IdeStreamBinaryFrame.HttpEnd ignored -> {
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
          closeBrowser(CloseStatus.BAD_DATA.withReason("invalid utf-8"));
          return false;
        }
        textPending = new byte[input.remaining()];
        input.get(textPending);
        chars.flip();
        return sendBrowserText(chars.toString(), segment.last() && textPending.length == 0);
      } catch (CharacterCodingException error) {
        closeBrowser(CloseStatus.BAD_DATA.withReason("invalid utf-8"));
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
        relay.sendWsSegment(pending.frame.text(), messageEnd, new byte[0]);
        return null;
      }
      while (offset < payload.length) {
        long credit = relay.sendableToDaemon();
        if (credit <= 0L) {
          return new Pending(pending.frame, offset);
        }
        int size = (int) Math.min(Math.min(payload.length - offset, credit), SEGMENT_BYTES);
        size = trimToCodepointBoundary(pending.frame.text(), payload, offset, size);
        if (size <= 0) {
          // 当前 credit 只够切在码点中间：等待 Daemon ACK 释放更多 credit，绝不发出非法 UTF-8。
          return new Pending(pending.frame, offset);
        }
        byte[] chunk = Arrays.copyOfRange(payload, offset, offset + size);
        offset += size;
        relay.sendWsSegment(pending.frame.text(), messageEnd && offset >= payload.length, chunk);
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
  }

  private record Pending(Frame frame, int offset) {}
}
