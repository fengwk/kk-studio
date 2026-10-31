package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;

import java.io.IOException;
import java.util.Optional;
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
  }
}
