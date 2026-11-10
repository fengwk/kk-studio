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
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 意图：bootstrap 与数据面只接受权威快照中仍匹配的 cookie；handler 只看 cookie 与快照，不维护第二份授权真相。 */
class IdeBrowserSessionHandlerAndAuthorizerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID BROWSER_SESSION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID EVENT_NODE = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CONNECTION = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CLAIM = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  @Test
  void authorizerScenarios() {
    Fixture fixture = fixture();

    assertFalse(fixture.authorizer.authorize(ENV, VIEW, null).isPresent());
    assertFalse(fixture.authorizer.authorize(ENV, VIEW, "other=abc").isPresent());
    assertFalse(
        fixture.authorizer.authorize(ENV, VIEW, "kk_ide_session=1; kk_ide_session=2").isPresent());

    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        fixture.authorizer.authorize(ENV, VIEW, "kk_ide_session=" + fixture.token);
    assertTrue(access.isPresent());
    assertEquals(VIEW, access.get().viewId());
    assertEquals(1L, access.get().bindingEpoch());
    assertTrue(access.get().authorizedWorkspaces().contains("/workspace"));
  }

  @Test
  void authorizerRejectsReplacedEpochOrRemovedView() {
    Fixture fixture = fixture();
    fixture.snapshots.accept(
        IDENTITY,
        2L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                BROWSER_SESSION,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                2L,
                "/dir",
                "/workspace",
                IdeViewState.OPEN)));

    assertFalse(
        fixture.authorizer.authorize(ENV, VIEW, "kk_ide_session=" + fixture.token).isPresent(),
        "旧 epoch cookie 不得因同一 view 的新快照继续有效");
  }

  @Test
  void accessRequiresPositiveEpochAndDefensivelyCopiesWorkspaces() {
    Set<String> workspaces = new HashSet<>();
    workspaces.add("/workspace");
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdeDataViewAuthorizer.IdeDataViewAccess(IDENTITY, VIEW, 0L, workspaces),
        "数据面授权不得接受非正 bindingEpoch");

    IdeDataViewAuthorizer.IdeDataViewAccess access =
        new IdeDataViewAuthorizer.IdeDataViewAccess(IDENTITY, VIEW, 1L, workspaces);
    workspaces.add("/other");

    assertEquals(Set.of("/workspace"), access.authorizedWorkspaces());
    assertThrows(
        UnsupportedOperationException.class,
        () -> access.authorizedWorkspaces().add("/other"),
        "授权 workspace 集合不得被调用方原地修改");
  }

  @Test
  void bootstrapSuccessAndFailure() throws IOException {
    Fixture fixture = fixture();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("token")).thenReturn("valid-ticket");
    when(request.getContextPath()).thenReturn("");
    when(request.isSecure()).thenReturn(true);
    HttpServletResponse response = mock(HttpServletResponse.class);

    fixture.handler.handleBootstrap(ENV, VIEW, request, response);

    verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
    verify(response).setHeader("Cache-Control", "no-store");

    HttpServletRequest invalid = mock(HttpServletRequest.class);
    when(invalid.getParameter("token")).thenReturn("invalid");
    HttpServletResponse invalidResponse = mock(HttpServletResponse.class);
    fixture.handler.handleBootstrap(ENV, VIEW, invalid, invalidResponse);
    verify(invalidResponse).sendError(HttpServletResponse.SC_GONE);
  }

  @Test
  void bootstrapWithoutCurrentSnapshotIsGone() throws IOException {
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry sessions = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    IdeViewSnapshotStore snapshots =
        new IdeViewSnapshotStore(environmentId -> Optional.of(IDENTITY));
    when(tickets.consume("ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, "/workspace")));
    IdeBrowserSessionHandler handler =
        new IdeBrowserSessionHandler(tickets, sessions, snapshots, transport);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("token")).thenReturn("ticket");
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, VIEW, request, response);

    verify(response).sendError(HttpServletResponse.SC_GONE);
  }

  @Test
  void bootstrapRejectsTicketOutsideUrlScopeOrReplacedInstance() throws IOException {
    Fixture otherEnvironment = fixture();
    HttpServletRequest otherEnvironmentRequest = mock(HttpServletRequest.class);
    when(otherEnvironmentRequest.getParameter("token")).thenReturn("valid-ticket");
    HttpServletResponse otherEnvironmentResponse = mock(HttpServletResponse.class);
    otherEnvironment.handler.handleBootstrap(
        EnvironmentId.parse("99999999-9999-4999-8999-999999999999"),
        VIEW,
        otherEnvironmentRequest,
        otherEnvironmentResponse);
    verify(otherEnvironmentResponse).sendError(HttpServletResponse.SC_GONE);

    Fixture otherView = fixture();
    HttpServletRequest otherViewRequest = mock(HttpServletRequest.class);
    when(otherViewRequest.getParameter("token")).thenReturn("valid-ticket");
    HttpServletResponse otherViewResponse = mock(HttpServletResponse.class);
    otherView.handler.handleBootstrap(ENV, UUID.randomUUID(), otherViewRequest, otherViewResponse);
    verify(otherViewResponse).sendError(HttpServletResponse.SC_GONE);

    Fixture replaced = fixture();
    when(replaced.transport.connectionIdentity(ENV))
        .thenReturn(Optional.of(new DaemonConnectionIdentity(ENV, "daemon-2")));
    HttpServletRequest replacedRequest = mock(HttpServletRequest.class);
    when(replacedRequest.getParameter("token")).thenReturn("valid-ticket");
    HttpServletResponse replacedResponse = mock(HttpServletResponse.class);
    replaced.handler.handleBootstrap(ENV, VIEW, replacedRequest, replacedResponse);
    verify(replacedResponse).sendError(HttpServletResponse.SC_GONE);
  }

  @Test
  void bootstrapEncodesWorkspacePathWithoutFormSemantics() throws IOException {
    String workspace = "/managed/my project/a+b";
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry sessions = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    IdeViewSnapshotStore snapshots =
        new IdeViewSnapshotStore(environmentId -> Optional.of(IDENTITY));
    snapshots.accept(
        IDENTITY,
        1L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                BROWSER_SESSION,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                1L,
                "/dir",
                workspace,
                IdeViewState.OPEN)));
    when(tickets.consume("ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, workspace)));
    IdeBrowserSessionHandler handler =
        new IdeBrowserSessionHandler(tickets, sessions, snapshots, transport);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("token")).thenReturn("ticket");
    when(request.getContextPath()).thenReturn("");
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, VIEW, request, response);

    ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("Location"), location.capture());
    assertTrue(
        location.getValue().endsWith("?workspace=%2Fmanaged%2Fmy%20project%2Fa%2Bb"),
        location.getValue());
  }

  @Test
  void sessionProbeChecksCookieSnapshotAndGeneration() {
    Fixture fixture = fixture();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getCookies())
        .thenReturn(
            new Cookie[] {new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, fixture.token)});
    HttpServletResponse response = mock(HttpServletResponse.class);

    fixture.handler.handleSession(ENV, VIEW, request, response);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);

    HttpServletRequest noCookie = mock(HttpServletRequest.class);
    HttpServletResponse noCookieResponse = mock(HttpServletResponse.class);
    fixture.handler.handleSession(ENV, VIEW, noCookie, noCookieResponse);
    verify(noCookieResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    when(fixture.transport.connectionIdentity(ENV)).thenReturn(Optional.empty());
    HttpServletResponse disconnected = mock(HttpServletResponse.class);
    fixture.handler.handleSession(ENV, VIEW, request, disconnected);
    verify(disconnected).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
  }

  @Test
  void sessionProbeRejectsUnknownCookieAndStaleOrReplacedBinding() {
    Fixture fixture = fixture();

    HttpServletRequest unknownCookie = mock(HttpServletRequest.class);
    when(unknownCookie.getCookies())
        .thenReturn(
            new Cookie[] {
              new Cookie("other", "1"),
              new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, "unknown")
            });
    HttpServletResponse unknownCookieResponse = mock(HttpServletResponse.class);
    fixture.handler.handleSession(ENV, VIEW, unknownCookie, unknownCookieResponse);
    verify(unknownCookieResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    fixture.snapshots.accept(
        IDENTITY,
        2L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                BROWSER_SESSION,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                1L,
                "/dir",
                "/workspace",
                IdeViewState.CLOSING)));
    HttpServletRequest staleSnapshot = mock(HttpServletRequest.class);
    when(staleSnapshot.getCookies())
        .thenReturn(
            new Cookie[] {new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, fixture.token)});
    HttpServletResponse staleSnapshotResponse = mock(HttpServletResponse.class);
    fixture.handler.handleSession(ENV, VIEW, staleSnapshot, staleSnapshotResponse);
    verify(staleSnapshotResponse).setStatus(HttpServletResponse.SC_CONFLICT);

    fixture.snapshots.accept(
        IDENTITY,
        3L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                BROWSER_SESSION,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                1L,
                "/dir",
                "/workspace",
                IdeViewState.OPEN)));
    when(fixture.transport.connectionIdentity(ENV))
        .thenReturn(Optional.of(new DaemonConnectionIdentity(ENV, "daemon-2")));
    HttpServletRequest replaced = mock(HttpServletRequest.class);
    when(replaced.getCookies())
        .thenReturn(
            new Cookie[] {new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, fixture.token)});
    HttpServletResponse replacedResponse = mock(HttpServletResponse.class);
    fixture.handler.handleSession(ENV, VIEW, replaced, replacedResponse);
    verify(replacedResponse).setStatus(HttpServletResponse.SC_CONFLICT);
  }

  private static Fixture fixture() {
    IdeBootstrapTicketService tickets = mock(IdeBootstrapTicketService.class);
    IdeBrowserSessionRegistry sessions = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    IdeViewSnapshotStore snapshots =
        new IdeViewSnapshotStore(environmentId -> Optional.of(IDENTITY));
    snapshots.accept(
        IDENTITY,
        1L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                BROWSER_SESSION,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                1L,
                "/dir",
                "/workspace",
                IdeViewState.OPEN)));
    String token = sessions.open(IDENTITY, VIEW.toString(), 1L, "/workspace");
    when(tickets.consume("valid-ticket"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(IDENTITY, VIEW.toString(), 1L, "/workspace")));
    IdeBrowserSessionHandler handler =
        new IdeBrowserSessionHandler(tickets, sessions, snapshots, transport);
    SessionIdeDataViewAuthorizer authorizer =
        new SessionIdeDataViewAuthorizer(sessions, snapshots, transport);
    return new Fixture(handler, authorizer, transport, snapshots, token);
  }

  private record Fixture(
      IdeBrowserSessionHandler handler,
      SessionIdeDataViewAuthorizer authorizer,
      IdeCommandTransport transport,
      IdeViewSnapshotStore snapshots,
      String token) {}
}
