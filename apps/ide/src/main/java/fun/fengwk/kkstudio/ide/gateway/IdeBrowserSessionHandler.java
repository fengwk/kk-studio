package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.uri.UrlEncoding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 浏览器 IDE 的 bootstrap/session 入口：只处理 view 基址下的两个保留端点。
 *
 * <p>bootstrap 消费一次性票据后，只依据唯一权威视图快照确认 ticket 的实例/view/epoch/workspace，再设置 view 作用域的 HttpOnly
 * cookie；session 只报告当前 cookie、权威快照与代际是否仍可进入数据面。
 */
@Component
public final class IdeBrowserSessionHandler {

  private final IdeBootstrapTicketService bootstrapTickets;
  private final IdeBrowserSessionRegistry sessions;
  private final IdeViewSnapshotStore snapshots;
  private final IdeCommandTransport transport;

  public IdeBrowserSessionHandler(
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserSessionRegistry sessions,
      IdeViewSnapshotStore snapshots,
      IdeCommandTransport transport) {
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  /** 消费一次性 bootstrap ticket 并设置 view 作用域 cookie。 */
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
    boolean current =
        snapshots
            .snapshot(ticket.identity())
            .flatMap(snapshot -> snapshot.find(viewId))
            .filter(view -> view.state() == IdeViewState.OPEN)
            .filter(view -> view.bindingEpoch() == ticket.bindingEpoch())
            .filter(view -> view.workspacePath().equals(ticket.workspacePath()))
            .isPresent();
    if (!current) {
      response.sendError(HttpServletResponse.SC_GONE);
      return;
    }
    String cookieValue =
        sessions.open(
            ticket.identity(), ticket.viewId(), ticket.bindingEpoch(), ticket.workspacePath());

    String base = IdeDataPaths.viewBase(request, environmentId, viewId);
    StringBuilder cookie =
        new StringBuilder(IdeGatewayConstants.SESSION_COOKIE_NAME)
            .append('=')
            .append(cookieValue)
            .append("; Path=")
            .append(base)
            .append("; Max-Age=")
            .append(Math.max(1L, IdeGatewayConstants.DEFAULT_SESSION_TTL.toSeconds()))
            .append("; HttpOnly; SameSite=Strict");
    if (request.isSecure()) {
      cookie.append("; Secure");
    }
    response.addHeader("Set-Cookie", cookie.toString());
    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
    response.setHeader(
        "Location", base + "?workspace=" + UrlEncoding.encode(ticket.workspacePath()));
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
    boolean current =
        snapshots
            .snapshot(session.identity())
            .flatMap(snapshot -> snapshot.find(viewId))
            .filter(view -> view.state() == IdeViewState.OPEN)
            .filter(view -> view.bindingEpoch() == session.bindingEpoch())
            .filter(view -> view.workspacePath().equals(session.workspacePath()))
            .isPresent();
    if (!current) {
      response.setStatus(HttpServletResponse.SC_CONFLICT);
      return;
    }
    int rejection = generationRejection(IdeGenerationState.of(transport, session.identity()));
    response.setStatus(rejection == 0 ? HttpServletResponse.SC_NO_CONTENT : rejection);
  }

  private IdeBrowserSessionRegistry.Session session(
      HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String cookieName = IdeGatewayConstants.SESSION_COOKIE_NAME;
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
}
