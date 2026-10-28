package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 浏览器 IDE 的 bootstrap/session 入口：只处理 view 基址下的两个保留端点。
 *
 * <p>bootstrap 消费一次性票据后设置 view 作用域的 HttpOnly cookie；cookie 绑定 Environment、Daemon
 * 实例、viewId、bindingEpoch 与 受管 workspace，因此不能跨 view 共享。session 只报告当前 cookie/代际是否仍可进入数据面，不续租、不重建视图。
 *
 * <p>过期/已消费票据、view 不匹配、控制代际被替换立即 410；会话 cookie 不匹配或过期 401；同一实例暂时断线 503。
 */
@Component
public final class IdeBrowserSessionHandler {

  private static final String COOKIE_PATH_PREFIX = "/environment-ide/";

  private final IdeBootstrapTicketService bootstrapTickets;
  private final IdeBrowserSessionRegistry sessions;
  private final IdeCommandTransport transport;
  private final IdeGatewayProperties properties;

  public IdeBrowserSessionHandler(
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserSessionRegistry sessions,
      IdeCommandTransport transport,
      IdeGatewayProperties properties) {
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * 消费一次性 bootstrap ticket 并设置 view 作用域 cookie。
   *
   * @param viewId URL 中的 canonical viewId
   * @param request 浏览器请求；token 必须来自 query
   */
  public void handleBootstrap(
      EnvironmentId environmentId,
      UUID viewId,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    response.setHeader("Cache-Control", "no-store");
    Optional<IdeBootstrapTicketService.Ticket> consumed =
        bootstrapTickets.consume(request.getParameter("token"));
    if (consumed.isEmpty()) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    IdeBootstrapTicketService.Ticket ticket = consumed.get();
    if (!ticket.environmentId().equals(environmentId)
        || !ticket.viewId().equals(viewId.toString())) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    if (IdeGenerationState.of(transport, ticket.identity()) == IdeGenerationState.REPLACED) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    String cookieValue;
    try {
      cookieValue =
          sessions.open(
              ticket.identity(), ticket.viewId(), ticket.bindingEpoch(), ticket.workspacePath());
    } catch (IllegalStateException error) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }

    String base = routeBase(request, environmentId, viewId);
    StringBuilder cookie =
        new StringBuilder(properties.requireSessionCookieName())
            .append('=')
            .append(cookieValue)
            .append("; Path=")
            .append(base)
            .append("; Max-Age=")
            .append(Math.max(1L, properties.requireSessionTtl().toSeconds()))
            .append("; HttpOnly; SameSite=Strict");
    if (request.isSecure()) {
      cookie.append("; Secure");
    }
    response.addHeader("Set-Cookie", cookie.toString());
    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
    response.setHeader(
        "Location",
        base + "?workspace=" + URLEncoder.encode(ticket.workspacePath(), StandardCharsets.UTF_8));
  }

  /** 探测当前 view 的 cookie；不刷新 cookie、不续租视图。 */
  public void handleSession(
      EnvironmentId environmentId,
      UUID viewId,
      HttpServletRequest request,
      HttpServletResponse response) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    response.setHeader("Cache-Control", "no-store");
    IdeBrowserSessionRegistry.Session session = session(request, environmentId, viewId);
    if (session == null) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    int rejection = generationRejection(IdeGenerationState.of(transport, session.identity()));
    response.setStatus(rejection == 0 ? HttpServletResponse.SC_NO_CONTENT : rejection);
  }

  private IdeBrowserSessionRegistry.Session session(
      HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String cookieName = properties.requireSessionCookieName();
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (!cookieName.equals(cookie.getName())) {
        continue;
      }
      Optional<IdeBrowserSessionRegistry.Session> session =
          sessions.lookup(cookie.getValue(), environmentId, viewId.toString());
      if (session.isPresent()) {
        return session.get();
      }
    }
    return null;
  }

  private static int generationRejection(IdeGenerationState state) {
    return switch (state) {
      case CURRENT -> 0;
      case DISCONNECTED -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
      case REPLACED -> HttpServletResponse.SC_CONFLICT;
    };
  }

  static String routeBase(HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String contextPath = request.getContextPath();
    String prefix = contextPath == null ? "" : contextPath;
    return prefix + COOKIE_PATH_PREFIX + environmentId + "/" + viewId + "/";
  }
}
