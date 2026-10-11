package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

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
import fun.fengwk.kkstudio.platform.environment.ide.IdeBootstrapTicketService;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IDE 独立 Origin 的数据面：把浏览器 HTTP exchange 经一次性 ticket 与 Daemon 反向连接配对后双向转发。
 *
 * <p>每个请求：解析 host-only 会话 cookie → 复核其绑定代际仍是当前 READY 代际 → 铸造一次性 stream ticket 并登记 pending 配对 → 通过
 * {@code OPEN_STREAM} 命令把 ticket + 受控 streamUrl 交给 Daemon → 等待反向连接后按 credit 双向转发请求体与响应。 请求/响应头都过滤
 * hop-by-hop 与 {@code content-length}；IDE 数据不进入 SQL/PG。
 *
 * <p>失败一律 fail-closed：无 cookie/代际不匹配 → 401/409；等待反向连接超时 → 504 并放弃在途 {@code OPEN_STREAM}；通道不可用 →
 * 503。任何路径都不重放副作用、不伪造响应体。
 */
@Component
public final class IdeGatewayDataPlane {

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade");

  private static final Set<String> DROPPED_REQUEST_HEADERS =
      Set.of("host", "content-length", "connection", "upgrade", "transfer-encoding");

  private static final int MAX_FORWARDED_HEADERS = 64;

  /** 单次入站轮询间隔：空闲轮询必须远小于 exchange 的真实 deadline，从而既能及时反应关闭也不误判慢速上游。 */
  private static final long POLL_INTERVAL_NANOS = 200_000_000L;

  private final IdeCommandTransport transport;
  private final IdeStreamTicketService streamTickets;
  private final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
  private final IdeGatewayProperties properties;
  private final IdeBrowserSessionRegistry sessions;
  private final IdeBootstrapTicketService bootstrapTickets;

  public IdeGatewayDataPlane(
      IdeCommandTransport transport,
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeGatewayProperties properties,
      IdeBrowserSessionRegistry sessions,
      IdeBootstrapTicketService bootstrapTickets) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
  }

  /**
   * 处理一个 IDE Origin 上的浏览器 HTTP exchange。
   *
   * @param rawPathAndQuery 原始 path（含 query），直接转发给 Daemon 侧的 code-server
   */
  public void handleHttp(
      EnvironmentId environmentId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    if (!properties.configured()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!csrfAllowed(request)) {
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
    if (!workspaceQueryAllowed(session, request)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    IdeStreamTicketService.Issued issued =
        streamTickets.issue(session.identity(), IdeStreamMode.HTTP);
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
    IdeStreamPeer peer = null;
    try {
      String streamUrl = properties.streamUrl(environmentId, issued.ticket());
      IdeCommand command =
          new IdeCommand(
              IdeAction.OPEN_STREAM,
              null,
              null,
              issued.ticket(),
              streamUrl,
              IdeStreamMode.HTTP,
              request.getMethod(),
              rawPathAndQuery,
              forwardRequestHeaders(request));
      IdeRequest ideRequest = transport.requestIde(environmentId, command);
      invocation.set(ideRequest.invocationId());
      peer = pairing.awaitPeer();
      Exchange exchange =
          new Exchange(
              response, peer, System.nanoTime() + properties.requireRequestTimeout().toNanos());
      exchange.run(request);
    } catch (IdeStreamPairingException error) {
      sendError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT);
    } catch (EnvironmentCapabilityUnavailableException
        | EnvironmentCapabilityBusyException
        | EnvironmentCapabilitySendUncertainException error) {
      sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    } finally {
      abandonOnce.run();
      pairing.close();
      if (peer != null) {
        peer.close();
      }
    }
  }

  /**
   * 消费一次性 bootstrap ticket，设置 host-only/HttpOnly 会话 cookie 并跳转到受管 workspace。
   *
   * <p>workspace 路径来自 Daemon 的 {@code OPEN_VIEW} 元数据（ticket 绑定），不接受浏览器提供的任意 path。
   */
  public void handleBootstrap(
      EnvironmentId environmentId, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    if (!properties.configured()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    Optional<IdeBootstrapTicketService.Ticket> consumed =
        bootstrapTickets.consume(request.getParameter("token"));
    if (consumed.isEmpty() || !consumed.get().environmentId().equals(environmentId)) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    IdeBootstrapTicketService.Ticket ticket = consumed.get();
    Optional<IdeSessionIdentity> current = transport.ideSessionIdentity(environmentId);
    if (current.isEmpty() || !current.get().equals(ticket.identity())) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    String cookieValue = sessions.open(ticket.identity(), ticket.workspacePath());
    StringBuilder cookie =
        new StringBuilder(properties.requireSessionCookieName())
            .append('=')
            .append(cookieValue)
            .append("; Path=/; HttpOnly; SameSite=Strict");
    if (request.isSecure()) {
      cookie.append("; Secure");
    }
    response.addHeader("Set-Cookie", cookie.toString());
    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
    response.setHeader(
        "Location",
        "/?workspace=" + URLEncoder.encode(ticket.workspacePath(), StandardCharsets.UTF_8));
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

  /** IDE 根路径上的 {@code workspace}/{@code folder} 只能指向 cookie 绑定的受管 workspace。 */
  private static boolean workspaceQueryAllowed(
      IdeBrowserSessionRegistry.Session session, HttpServletRequest request) {
    for (String name : List.of("workspace", "folder")) {
      String value = request.getParameter(name);
      if (value != null && !value.equals(session.workspacePath())) {
        return false;
      }
    }
    return true;
  }

  /** 状态变更请求必须来自同源：{@code Sec-Fetch-Site}/{@code Origin} 都不能指示跨站。 */
  static boolean csrfAllowed(HttpServletRequest request) {
    String method = request.getMethod();
    if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
      return true;
    }
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none")) {
      return false;
    }
    String origin = request.getHeader("Origin");
    return origin == null || sameOrigin(origin, request);
  }

  private static boolean sameOrigin(String origin, HttpServletRequest request) {
    try {
      URI uri = new URI(origin);
      String host = uri.getHost();
      if (host == null) {
        return false;
      }
      int port = uri.getPort();
      String requestHost = request.getServerName();
      int requestPort = request.getServerPort();
      boolean hostMatches = host.equalsIgnoreCase(requestHost);
      boolean portMatches =
          port == -1
              || port == requestPort
              || (port == 443 && "https".equalsIgnoreCase(request.getScheme()))
              || (port == 80 && "http".equalsIgnoreCase(request.getScheme()));
      return hostMatches && portMatches;
    } catch (URISyntaxException error) {
      return false;
    }
  }

  static List<IdeHeader> forwardRequestHeaders(HttpServletRequest request) {
    List<IdeHeader> headers = new ArrayList<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return headers;
    }
    while (names.hasMoreElements() && headers.size() < MAX_FORWARDED_HEADERS) {
      String name = names.nextElement();
      String lower = name.toLowerCase(Locale.ROOT);
      if (HOP_BY_HOP.contains(lower) || DROPPED_REQUEST_HEADERS.contains(lower)) {
        continue;
      }
      Enumeration<String> values = request.getHeaders(name);
      while (values.hasMoreElements() && headers.size() < MAX_FORWARDED_HEADERS) {
        String value = values.nextElement();
        try {
          headers.add(new IdeHeader(name, value));
        } catch (IllegalArgumentException ignored) {
          // 非法 header 名/值不进入 wire；丢弃而不是让整个请求失败。
        }
      }
    }
    return headers;
  }

  private static boolean isHopByHop(String name) {
    return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
  }

  private static void sendError(HttpServletResponse response, int status) throws IOException {
    if (!response.isCommitted()) {
      response.sendError(status);
    }
  }

  /** 单条 exchange 的转发状态：请求体发送与响应读取共用同一入站泵，保证 credit 与顺序一致。 */
  private final class Exchange {

    private final HttpServletResponse response;
    private final IdeStreamRelay relay;
    private final IdeStreamPeer peer;
    private final long deadlineNanos;
    private boolean responseStarted;
    private boolean done;
    private boolean failed;
    private boolean closed;

    private Exchange(HttpServletResponse response, IdeStreamPeer peer, long deadlineNanos) {
      this.response = response;
      this.peer = peer;
      this.deadlineNanos = deadlineNanos;
      this.relay =
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

    private void run(HttpServletRequest request) throws IOException {
      sendRequestBody(request);
      readResponse();
      if (!failed) {
        response.getOutputStream().flush();
      }
    }

    /**
     * 转发请求体并在结束后发送 HTTP END。
     *
     * <p>与 Node peer 一致：所有 method（含 GET/HEAD）都走同一条「流式 body + END」路径，绝不因为 method 丢弃 body；无 body 时
     * {@code read} 立即返回 -1，只发送 END。
     */
    private void sendRequestBody(HttpServletRequest request) throws IOException {
      try (InputStream input = request.getInputStream()) {
        byte[] buffer = new byte[DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES];
        int read;
        while ((read = input.read(buffer)) > 0) {
          ensureCredit(read);
          if (failed || closed || done) {
            return;
          }
          byte[] chunk = new byte[read];
          System.arraycopy(buffer, 0, chunk, 0, read);
          relay.sendHttpBody(chunk);
        }
      }
      relay.sendHttpEnd();
    }

    private void readResponse() throws IOException {
      while (!done && !failed && !closed) {
        pump();
      }
      if (failed) {
        throw new IOException("ide reverse stream reported a failure");
      }
      if (!done) {
        throw new IOException("ide reverse stream closed before the response completed");
      }
    }

    private void ensureCredit(int bytes) throws IOException {
      while (!done && !failed && !closed && relay.sendableToDaemon() < bytes) {
        pump();
      }
    }

    private void pump() throws IOException {
      IdeStreamPeer.Inbound inbound = nextInbound();
      if (inbound == null) {
        // 空闲轮询（非关闭）不应终止 exchange：nextInbound 只在 peer.closed() 时置位 closed。
        return;
      }
      if (inbound instanceof IdeStreamPeer.Inbound.Text text) {
        handleText(text.json());
      } else {
        handleBinary(((IdeStreamPeer.Inbound.Binary) inbound).frame());
      }
    }

    /**
     * 取下一个入站帧。
     *
     * <p>{@code poll} 返回 {@code null} 只表示本次没有入站帧（空闲），不是连接关闭：慢速 IDE 可能超过一个 poll 间隔才产生首字节。只有 {@link
     * IdeStreamPeer#closed()} 才视为关闭，其余情况继续等待到本次 exchange 的真实 deadline。
     */
    private IdeStreamPeer.Inbound nextInbound() throws IOException {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0L) {
        throw new SocketTimeoutException("ide exchange timed out");
      }
      Duration wait = Duration.ofNanos(Math.min(remaining, POLL_INTERVAL_NANOS));
      try {
        IdeStreamPeer.Inbound inbound = peer.poll(wait);
        if (inbound == null && peer.closed()) {
          closed = true;
        }
        return inbound;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("ide exchange was interrupted", error);
      }
    }

    private void handleText(String json) {
      IdeStreamControlFrame frame = relay.receiveText(json);
      if (frame instanceof IdeStreamControlFrame.HttpResponse httpResponse) {
        applyResponse(httpResponse);
      } else {
        // ERROR 或 HTTP 模式下的 WS_READY 都属于不可继续的失败。
        failed = true;
      }
    }

    private void handleBinary(byte[] frame) throws IOException {
      IdeStreamBinaryFrame decoded = relay.receiveBinary(frame);
      switch (decoded) {
        case IdeStreamBinaryFrame.HttpBody body -> {
          if (!responseStarted) {
            failed = true;
            return;
          }
          response.getOutputStream().write(body.bytes());
          relay.ackFromDaemon(body.bytes().length);
        }
        case IdeStreamBinaryFrame.HttpEnd ignored -> done = true;
        case IdeStreamBinaryFrame.Ack ignored -> {
          // 仅用于释放发送 credit。
        }
        case IdeStreamBinaryFrame.WsSegment ignored -> failed = true;
      }
    }

    private void applyResponse(IdeStreamControlFrame.HttpResponse httpResponse) {
      response.setStatus(httpResponse.status());
      for (IdeHeader header : httpResponse.headers()) {
        if (isHopByHop(header.name()) || "content-length".equalsIgnoreCase(header.name())) {
          continue;
        }
        response.addHeader(header.name(), header.value());
      }
      responseStarted = true;
    }
  }
}
