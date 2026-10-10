package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IDE 独立 Origin 上浏览器原生 WebSocket 的数据面：把浏览器 WS 升级与 Daemon 反向连接配对后双向转发分段。
 *
 * <p>与 HTTP 数据面共用同一 ticket/配对/credit 机制，差别只在 wire 语义：Daemon 先回 {@code WS_READY} 再收发分段，浏览器侧的每条 WS
 * 消息被切成逐段（≤64 KiB）发送，Daemon 侧的分段按 FIN 重组为单条浏览器消息。credit 等待与 ACK 都在同一条转发线程内串行完成，因此 「OPEN_STREAM
 * 回执慢于数据流」不会造成死锁：任一方向都只在窗口允许时才发送，其余时间在轮询中推进另一方向。
 */
@Slf4j
@Component
public final class IdeBrowserWebSocketGateway {

  private static final int MAX_INBOUND_MESSAGES = 64;
  private static final int MAX_WS_MESSAGE_BYTES = 16 * 1024 * 1024;
  private static final long POLL_TICK_MILLIS = 5L;

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
   * 处理一个 IDE Origin 上的浏览器 WS 升级：先做与 HTTP 数据面一致的 cookie/代际/同源校验，再执行容器级握手。
   *
   * <p>校验失败直接以 HTTP 状态码拒绝，不建立任何反向连接；成功则在连接建立后把该浏览器消息流交给配对后的 Daemon 连接。
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
    if (!IdeGatewayDataPlane.csrfAllowed(request)) {
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

  /** 浏览器连接侧的窄句柄：入站帧入有界队列，出站帧在会话锁内串行发送。 */
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
    public void afterConnectionEstablished(WebSocketSession browser) {
      BrowserStreamSession stream =
          new BrowserStreamSession(browser, environmentId, session, rawPathAndQuery, headers);
      active.put(browser.getId(), stream);
      stream.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
      offer(browser, new Message(true, message.getPayload().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
      ByteBuffer payload = message.getPayload();
      byte[] bytes = new byte[payload.remaining()];
      payload.get(bytes);
      offer(browser, new Message(false, bytes));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
      detach(browser, false);
    }

    @Override
    public void handleTransportError(WebSocketSession browser, Throwable exception) {
      detach(browser, true);
    }

    private void offer(WebSocketSession browser, Message message) {
      BrowserStreamSession stream = active.get(browser.getId());
      if (stream != null) {
        stream.offer(message);
      }
    }

    private void detach(WebSocketSession browser, boolean error) {
      BrowserStreamSession stream = active.remove(browser.getId());
      if (stream != null) {
        stream.onBrowserDetached();
      }
      if (error && browser.isOpen()) {
        try {
          browser.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
          // 浏览器侧已断开。
        }
      }
    }
  }

  private record Message(boolean text, byte[] payload) {}

  /** 一条浏览器 WS 会话的转发状态机：单线程内推进「浏览器→Daemon」与「Daemon→浏览器」两个方向。 */
  private final class BrowserStreamSession {

    private final WebSocketSession browser;
    private final EnvironmentId environmentId;
    private final IdeBrowserSessionRegistry.Session session;
    private final String rawPathAndQuery;
    private final List<IdeHeader> headers;
    private final BlockingQueue<Message> inbound = new ArrayBlockingQueue<>(MAX_INBOUND_MESSAGES);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread thread;

    private volatile IdeStreamPeer peer;

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
      this.thread = new Thread(this::run, "ide-browser-stream-" + browser.getId());
      this.thread.setDaemon(true);
    }

    void start() {
      thread.start();
    }

    void offer(Message message) {
      if (!inbound.offer(message)) {
        // 有界队列溢出：fail-closed，绝不无界缓冲浏览器字节。
        closeBrowser(CloseStatus.POLICY_VIOLATION);
        stopped.set(true);
      }
    }

    void onBrowserDetached() {
      stopped.set(true);
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
                properties.streamUrl(issued.ticket()),
                IdeStreamMode.WS,
                "GET",
                rawPathAndQuery,
                headers);
        IdeRequest request = transport.requestIde(environmentId, command);
        invocation.set(request.invocationId());
        peer = pairing.awaitPeer();
        long deadline = System.nanoTime() + properties.requireRequestTimeout().toNanos();
        if (awaitReady(deadline)) {
          relay(deadline);
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

    /** 等待 Daemon 的 {@code WS_READY}；此前的任何数据帧都是协议错误。 */
    private boolean awaitReady(long deadline) {
      while (!stopped.get() && System.nanoTime() < deadline) {
        IdeStreamPeer.Inbound frame = poll(deadline, POLL_TICK_MILLIS);
        if (frame == null) {
          if (peer.closed()) {
            return false;
          }
          continue;
        }
        if (frame instanceof IdeStreamPeer.Inbound.Text text) {
          IdeStreamControlFrame control = relay().receiveText(text.json());
          return control instanceof IdeStreamControlFrame.WsReady;
        }
        return false;
      }
      return false;
    }

    /** 双方向转发主循环：只在 credit 允许时发送分段，其余时间轮询另一方向。 */
    private void relay(long deadline) {
      Pending pending = null;
      ByteArrayOutputStream reassembly = new ByteArrayOutputStream();
      boolean reassemblyText = false;
      while (!stopped.get()) {
        if (pending == null) {
          Message message = inbound.poll();
          if (message != null) {
            pending = new Pending(message);
          }
        }
        if (pending != null) {
          pending = flush(pending);
        }
        IdeStreamPeer.Inbound frame = poll(deadline, pending == null ? POLL_TICK_MILLIS : 0L);
        if (frame == null) {
          if (peer.closed()) {
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
          continue;
        }
        if (frame instanceof IdeStreamPeer.Inbound.Text text) {
          IdeStreamControlFrame control = relay().receiveText(text.json());
          if (control instanceof IdeStreamControlFrame.Error) {
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
          continue;
        }
        IdeStreamBinaryFrame decoded =
            relay().receiveBinary(((IdeStreamPeer.Inbound.Binary) frame).frame());
        switch (decoded) {
          case IdeStreamBinaryFrame.WsSegment segment -> {
            if (reassembly.size() == 0) {
              reassemblyText = segment.text();
            }
            reassembly.write(segment.bytes(), 0, segment.bytes().length);
            if (reassembly.size() > MAX_WS_MESSAGE_BYTES) {
              closeBrowser(CloseStatus.TOO_BIG_TO_PROCESS);
              return;
            }
            if (segment.last()) {
              byte[] message = reassembly.toByteArray();
              reassembly.reset();
              if (!sendToBrowser(reassemblyText, message)) {
                return;
              }
            }
            relay().ackFromDaemon(segment.bytes().length);
          }
          case IdeStreamBinaryFrame.Ack ignored -> {
            // 仅用于释放发送 credit。
          }
          case IdeStreamBinaryFrame.HttpBody ignored -> {
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
          case IdeStreamBinaryFrame.HttpEnd ignored -> {
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
        }
      }
      closeBrowser(CloseStatus.NORMAL);
    }

    /** 把一条浏览器消息按 credit 逐段发给 Daemon；返回仍未发完的剩余部分。 */
    private Pending flush(Pending pending) {
      int offset = pending.offset;
      byte[] payload = pending.message.payload();
      if (payload.length == 0) {
        relay().sendWsSegment(pending.message.text(), true, new byte[0]);
        return null;
      }
      while (offset < payload.length) {
        long credit = relay().sendableToDaemon();
        if (credit <= 0L) {
          return new Pending(pending.message, offset);
        }
        int size =
            (int)
                Math.min(
                    Math.min(payload.length - offset, credit),
                    DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES);
        byte[] chunk = Arrays.copyOfRange(payload, offset, offset + size);
        offset += size;
        relay().sendWsSegment(pending.message.text(), offset >= payload.length, chunk);
      }
      return null;
    }

    private boolean sendToBrowser(boolean text, byte[] message) {
      WebSocketMessage<?> frame =
          text
              ? new TextMessage(new String(message, StandardCharsets.UTF_8))
              : new BinaryMessage(message);
      synchronized (browser) {
        if (browser.isOpen()) {
          try {
            browser.sendMessage(frame);
            return true;
          } catch (IOException error) {
            return false;
          }
        }
      }
      return false;
    }

    private void closeBrowser(CloseStatus status) {
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

    private IdeStreamPeer.Inbound poll(long deadline, long waitMillis) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        throw new IdeStreamPairingException("ide websocket exchange timed out");
      }
      long capped = Math.min(waitMillis, Math.max(1L, remaining / 1_000_000L));
      try {
        return peer.poll(Duration.ofMillis(capped));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IdeStreamPairingException("ide websocket exchange was interrupted");
      }
    }

    private IdeStreamRelay relay() {
      return relay;
    }

    private final IdeStreamRelay relay =
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
  }

  private record Pending(Message message, int offset) {

    Pending(Message message) {
      this(message, 0);
    }
  }
}
