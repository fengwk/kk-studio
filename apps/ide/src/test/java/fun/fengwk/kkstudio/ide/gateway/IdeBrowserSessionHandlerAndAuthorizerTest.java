package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class IdeBrowserSessionHandlerAndAuthorizerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  @Test
  void authorizerScenarios() {
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));

    registry.viewOpened(IDENTITY, VIEW.toString(), 1L, "/workspace");
    String token = registry.open(IDENTITY, VIEW.toString(), 1L, "/workspace");

    SessionIdeDataViewAuthorizer authorizer = new SessionIdeDataViewAuthorizer(registry, transport);

    assertFalse(authorizer.authorize(ENV, VIEW, null).isPresent());
    assertFalse(authorizer.authorize(ENV, VIEW, "other=abc").isPresent());
    assertFalse(authorizer.authorize(ENV, VIEW, "kk_ide_session=1; kk_ide_session=2").isPresent());

    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        authorizer.authorize(ENV, VIEW, "kk_ide_session=" + token);
    assertTrue(access.isPresent());
    assertEquals(VIEW, access.get().viewId());
    assertEquals(1L, access.get().bindingEpoch());
    assertTrue(access.get().authorizedWorkspaces().contains("/workspace"));
  }

  @Test
  void handlerBootstrapSuccessAndFailure() throws IOException {
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));

    registry.viewOpened(IDENTITY, VIEW.toString(), 1L, "/workspace");
    when(tickets.consume("valid-ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, "/workspace")));

    IdeBrowserSessionHandler handler = new IdeBrowserSessionHandler(tickets, registry, transport);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("token")).thenReturn("valid-ticket");
    when(req.getContextPath()).thenReturn("");
    when(req.isSecure()).thenReturn(true);
    HttpServletResponse res = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, VIEW, req, res);

    verify(res).setStatus(HttpServletResponse.SC_SEE_OTHER);
    verify(res).setHeader("Cache-Control", "no-store");

    // Invalid ticket
    HttpServletRequest invalidReq = mock(HttpServletRequest.class);
    when(invalidReq.getParameter("token")).thenReturn("invalid");
    HttpServletResponse invalidRes = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, invalidReq, invalidRes);
    verify(invalidRes).sendError(HttpServletResponse.SC_GONE);
  }

  /** bootstrap 重定向的 workspace 参数按 RFC 3986 编码：空格是 %20，不使用表单语义的 +。 */
  @Test
  void handlerBootstrapEncodesWorkspacePathWithoutFormSemantics() throws IOException {
    String workspace = "/managed/my project/a+b";
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    registry.viewOpened(IDENTITY, VIEW.toString(), 1L, workspace);
    when(tickets.consume("ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, workspace)));

    IdeBrowserSessionHandler handler = new IdeBrowserSessionHandler(tickets, registry, transport);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("token")).thenReturn("ticket");
    when(req.getContextPath()).thenReturn("");
    HttpServletResponse res = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, VIEW, req, res);

    ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
    verify(res).setHeader(eq("Location"), location.capture());
    assertTrue(
        location.getValue().endsWith("?workspace=%2Fmanaged%2Fmy%20project%2Fa%2Bb"),
        location.getValue());
  }

  @Test
  void handlerSessionProbe() {
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));

    registry.viewOpened(IDENTITY, VIEW.toString(), 1L, "/workspace");
    String token = registry.open(IDENTITY, VIEW.toString(), 1L, "/workspace");

    IdeBrowserSessionHandler handler = new IdeBrowserSessionHandler(tickets, registry, transport);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getCookies())
        .thenReturn(new Cookie[] {new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, token)});
    HttpServletResponse res = mock(HttpServletResponse.class);

    handler.handleSession(ENV, VIEW, req, res);
    verify(res).setStatus(HttpServletResponse.SC_NO_CONTENT);

    // No cookie
    HttpServletRequest noCookieReq = mock(HttpServletRequest.class);
    HttpServletResponse noCookieRes = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, noCookieReq, noCookieRes);
    verify(noCookieRes).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    // Cookie name does not match
    HttpServletRequest otherCookieReq = mock(HttpServletRequest.class);
    when(otherCookieReq.getCookies()).thenReturn(new Cookie[] {new Cookie("other", "value")});
    HttpServletResponse otherCookieRes = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, otherCookieReq, otherCookieRes);
    verify(otherCookieRes).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    // Generation disconnected -> 503
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.empty());
    HttpServletResponse discRes = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, req, discRes);
    verify(discRes).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

    // Generation replaced -> 409
    when(transport.connectionIdentity(ENV))
        .thenReturn(Optional.of(new DaemonConnectionIdentity(ENV, "daemon-new")));
    HttpServletResponse replRes = mock(HttpServletResponse.class);
    handler.handleSession(ENV, VIEW, req, replRes);
    verify(replRes).setStatus(HttpServletResponse.SC_CONFLICT);
  }

  @Test
  void handlerBootstrapMismatchesAndReplaced() throws IOException {
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));

    IdeBrowserSessionHandler handler = new IdeBrowserSessionHandler(tickets, registry, transport);

    // Ticket environment mismatch -> 410
    EnvironmentId otherEnv = EnvironmentId.parse("33333333-3333-3333-3333-333333333333");
    when(tickets.consume("other-env"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(
                    new DaemonConnectionIdentity(otherEnv, "daemon-1"),
                    VIEW.toString(),
                    1L,
                    "/workspace")));
    HttpServletRequest req1 = mock(HttpServletRequest.class);
    when(req1.getParameter("token")).thenReturn("other-env");
    HttpServletResponse res1 = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, req1, res1);
    verify(res1).sendError(HttpServletResponse.SC_GONE);

    // Ticket view mismatch -> 410
    when(tickets.consume("other-view"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(
                    IDENTITY, UUID.randomUUID().toString(), 1L, "/workspace")));
    HttpServletRequest req2 = mock(HttpServletRequest.class);
    when(req2.getParameter("token")).thenReturn("other-view");
    HttpServletResponse res2 = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, req2, res2);
    verify(res2).sendError(HttpServletResponse.SC_GONE);

    // Generation replaced -> 410
    when(transport.connectionIdentity(ENV))
        .thenReturn(Optional.of(new DaemonConnectionIdentity(ENV, "daemon-new")));
    when(tickets.consume("valid-ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, "/workspace")));
    HttpServletRequest req3 = mock(HttpServletRequest.class);
    when(req3.getParameter("token")).thenReturn("valid-ticket");
    HttpServletResponse res3 = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, req3, res3);
    verify(res3).sendError(HttpServletResponse.SC_GONE);

    // Session open fails (view not opened in registry) -> 410
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    HttpServletResponse res4 = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, req3, res4);
    verify(res4).sendError(HttpServletResponse.SC_GONE);

    // Non-secure request (no ; Secure)
    registry.viewOpened(IDENTITY, VIEW.toString(), 1L, "/workspace");
    HttpServletRequest req5 = mock(HttpServletRequest.class);
    when(req5.getParameter("token")).thenReturn("valid-ticket");
    when(req5.getContextPath()).thenReturn("");
    when(req5.isSecure()).thenReturn(false);
    HttpServletResponse res5 = mock(HttpServletResponse.class);
    handler.handleBootstrap(ENV, VIEW, req5, res5);
    verify(res5).setStatus(HttpServletResponse.SC_SEE_OTHER);
  }

  @Test
  void ideDataViewAccessValidation() {
    assertThrows(
        NullPointerException.class,
        () -> new IdeDataViewAuthorizer.IdeDataViewAccess(null, VIEW, 1L, Set.of("/workspace")));
    assertThrows(
        NullPointerException.class,
        () ->
            new IdeDataViewAuthorizer.IdeDataViewAccess(IDENTITY, null, 1L, Set.of("/workspace")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeDataViewAuthorizer.IdeDataViewAccess(IDENTITY, VIEW, 0L, Set.of("/workspace")));
  }
}
