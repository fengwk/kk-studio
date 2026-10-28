package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图：bootstrap 只消费绑定当前环境+view+epoch 的一次性票据，并设置 Path 限定到该 view 的 HttpOnly cookie；session 对过期/换代/跨
 * view cookie 立即拒绝，绝不复用浏览器存储的旧 epoch 凭据。
 */
class IdeBrowserSessionHandlerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID OTHER_VIEW = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final String DAEMON = "daemon-instance";
  private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

  private FixedTransport transport;
  private InMemoryIdeBootstrapTicketService tickets;
  private IdeBrowserSessionRegistry sessions;
  private IdeGatewayProperties properties;
  private IdeBrowserSessionHandler handler;

  @BeforeEach
  void setUp() {
    transport = new FixedTransport(new DaemonConnectionIdentity(ENV, DAEMON));
    tickets = new InMemoryIdeBootstrapTicketService();
    sessions =
        new IdeBrowserSessionRegistry(
            new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));
    properties = new IdeGatewayProperties();
    properties.setSessionCookieName("kk_ide_session");
    properties.setSessionTtl(Duration.ofMinutes(30));
    handler = new IdeBrowserSessionHandler(tickets, sessions, transport, properties);
  }

  @Test
  void bootstrapSetsViewScopedCookieAndRedirectsToManagedWorkspace() throws Exception {
    String token =
        tickets.issue(
            new DaemonConnectionIdentity(ENV, DAEMON), VIEW.toString(), 4L, "/managed/ws");
    HttpServletRequest request = request(token);
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, VIEW, request, response);

    verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
    ArgumentCaptor<String> cookie = ArgumentCaptor.forClass(String.class);
    verify(response).addHeader(eq("Set-Cookie"), cookie.capture());
    String header = cookie.getValue();
    assertTrue(header.startsWith("kk_ide_session="));
    assertTrue(header.contains("; Path=/environment-ide/" + ENV + "/" + VIEW + "/"));
    assertTrue(header.contains("; HttpOnly"));
    assertTrue(header.contains("; SameSite=Strict"));
    assertTrue(header.contains("; Max-Age=1800"));
    assertFalse(header.contains("; Secure"));

    ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("Location"), location.capture());
    assertTrue(
        location
            .getValue()
            .startsWith("/environment-ide/" + ENV + "/" + VIEW + "/?workspace=%2Fmanaged%2Fws"));

    String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    assertTrue(
        sessions
            .lookup(value, ENV, VIEW.toString())
            .orElseThrow()
            .authorizesWorkspace("/managed/ws"));

    HttpServletRequest sessionRequest = mock(HttpServletRequest.class);
    when(sessionRequest.getCookies())
        .thenReturn(new Cookie[] {new Cookie("kk_ide_session", value)});
    HttpServletResponse sessionResponse = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, sessionRequest, sessionResponse);
    verify(sessionResponse).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void bootstrapRejectsTicketForAnotherViewOrGeneration() throws Exception {
    String wrongView =
        tickets.issue(new DaemonConnectionIdentity(ENV, DAEMON), OTHER_VIEW.toString(), 4L, "/ws");
    HttpServletResponse response = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, request(wrongView), response);
    verify(response).sendError(HttpServletResponse.SC_GONE);

    transport.identity = new DaemonConnectionIdentity(ENV, "replaced-daemon");
    String replaced =
        tickets.issue(new DaemonConnectionIdentity(ENV, DAEMON), VIEW.toString(), 4L, "/ws");
    HttpServletResponse replacedResponse = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, request(replaced), replacedResponse);
    verify(replacedResponse).sendError(HttpServletResponse.SC_GONE);
  }

  @Test
  void sessionRejectsCrossViewCookieAndReportsReplacedGeneration() throws Exception {
    String token =
        tickets.issue(new DaemonConnectionIdentity(ENV, DAEMON), VIEW.toString(), 4L, "/ws");
    HttpServletResponse bootstrapResponse = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, request(token), bootstrapResponse);
    ArgumentCaptor<String> cookie = ArgumentCaptor.forClass(String.class);
    verify(bootstrapResponse).addHeader(eq("Set-Cookie"), cookie.capture());
    String value =
        cookie
            .getValue()
            .substring(cookie.getValue().indexOf('=') + 1, cookie.getValue().indexOf(';'));

    HttpServletRequest otherView = mock(HttpServletRequest.class);
    when(otherView.getCookies()).thenReturn(new Cookie[] {new Cookie("kk_ide_session", value)});
    HttpServletResponse otherViewResponse = mock(HttpServletResponse.class);
    handler.handleSession(ENV, OTHER_VIEW, otherView, otherViewResponse);
    verify(otherViewResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    transport.identity = new DaemonConnectionIdentity(ENV, "replaced-daemon");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("kk_ide_session", value)});
    HttpServletResponse response = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, request, response);
    verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
  }

  private static HttpServletRequest request(String token) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("token")).thenReturn(token);
    when(request.getContextPath()).thenReturn("");
    when(request.isSecure()).thenReturn(false);
    return request;
  }

  private static final class FixedTransport implements IdeCommandTransport {

    private DaemonConnectionIdentity identity;

    private FixedTransport(DaemonConnectionIdentity identity) {
      this.identity = identity;
    }

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      throw new AssertionError("unexpected command");
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(identity);
    }

    @Override
    public void sendIdeLease(EnvironmentId environmentId, DaemonIdeLease lease) {
      throw new AssertionError("unexpected lease");
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }
}
