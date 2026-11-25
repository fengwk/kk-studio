package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.ide.gateway.IdeBrowserSessionHandler;
import fun.fengwk.kkstudio.ide.gateway.IdeBrowserSessionRegistry;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.workspace.ChatSpaceService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Exercises the IDE view cookie boundary with real accounts persisted in Testcontainers PostgreSQL.
 */
class IdeSourceOwnershipPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String PASSWORD = "two-user-password";
  private static final UUID VIEW_A = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID VIEW_B = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final DaemonConnectionIdentity DAEMON =
      new DaemonConnectionIdentity(ENV, "test-daemon");

  @Autowired private StudioAccountService accounts;
  @Autowired private ChatSpaceService chatSpaces;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ResourceAccess resourceAccess;

  /** Each logged-in user bootstraps a view; neither can use the other's view cookie. */
  @Test
  void authenticatedUsersCannotExchangeIdeViewCookies() throws IOException {
    StudioPrincipal first = authenticate("ide-owner-a");
    StudioPrincipal second = authenticate("ide-owner-b");

    IdeBootstrapTicketService tickets = new IdeBootstrapTicketService();
    IdeBrowserSessionRegistry sessions = new IdeBrowserSessionRegistry();
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(DAEMON));
    IdeViewSnapshotStore snapshots = new IdeViewSnapshotStore(environmentId -> Optional.of(DAEMON));
    snapshots.accept(DAEMON, 1L, List.of(view(VIEW_A), view(VIEW_B)));
    IdeBrowserSessionHandler handler =
        new IdeBrowserSessionHandler(tickets, sessions, snapshots, transport);

    String firstCookie = bootstrap(handler, tickets, first, VIEW_A, "/workspaces/first");
    String secondCookie = bootstrap(handler, tickets, second, VIEW_B, "/workspaces/second");
    assertNotEquals(firstCookie, secondCookie);

    assertSession(handler, first, VIEW_A, firstCookie, HttpServletResponse.SC_NO_CONTENT);
    assertSession(handler, second, VIEW_B, secondCookie, HttpServletResponse.SC_NO_CONTENT);
    assertSession(handler, first, VIEW_B, secondCookie, HttpServletResponse.SC_UNAUTHORIZED);
    assertSession(handler, second, VIEW_A, firstCookie, HttpServletResponse.SC_UNAUTHORIZED);
  }

  /** The source gate resolves session ownership through the real PostgreSQL workspace relation. */
  @Test
  void sourceGateUsesPersistedWorkspaceOwner() {
    StudioPrincipal first = authenticate("ide-source-owner-a");
    StudioPrincipal second = authenticate("ide-source-owner-b");
    UUID sessionId = UUID.randomUUID();
    String title = "IDE ownership source";
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        title);
    var space =
        chatSpaces.create(
            first,
            UUID.randomUUID(),
            new ChatSpaceService.Create(title, "root", "default-assistant", null, false));
    jdbc.update(
        """
        insert into workspace_resource (id, workspace_id, folder_id, session_id, position, agent_name)
        select ?, workspace_id, id, ?, 0, ? from workspace_folder where workspace_id = ?
        """,
        sessionId,
        sessionId,
        "default-assistant",
        space.id());

    resourceAccess.require(first, Kind.SESSION, sessionId);
    DomainException error =
        assertThrows(
            DomainException.class, () -> resourceAccess.require(second, Kind.SESSION, sessionId));
    assertEquals("RESOURCE_NOT_FOUND", error.reason());
  }

  private StudioPrincipal authenticate(String username) {
    accounts.create(username, PASSWORD, StudioRole.USER);
    String token = accounts.login(username, PASSWORD).token();
    return accounts.authenticate(token).orElseThrow();
  }

  private static IdeViewSnapshotStore.View view(UUID viewId) {
    return new IdeViewSnapshotStore.View(
        viewId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        "/home/user",
        viewId.equals(VIEW_A) ? "/workspaces/first" : "/workspaces/second",
        IdeViewState.OPEN);
  }

  private static String bootstrap(
      IdeBrowserSessionHandler handler,
      IdeBootstrapTicketService tickets,
      StudioPrincipal principal,
      UUID viewId,
      String workspacePath)
      throws IOException {
    String ticket = tickets.issue(DAEMON, viewId.toString(), 1L, workspacePath, principal.userId());
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("token")).thenReturn(ticket);
    when(request.getAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE)).thenReturn(principal);
    when(request.isSecure()).thenReturn(true);
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.handleBootstrap(ENV, viewId, request, response);

    ArgumentCaptor<String> setCookie = ArgumentCaptor.forClass(String.class);
    verify(response).addHeader(eq("Set-Cookie"), setCookie.capture());
    verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
    String cookie = setCookie.getValue().split(";", 2)[0];
    return cookie.substring((IdeGatewayConstants.SESSION_COOKIE_NAME + "=").length());
  }

  private static void assertSession(
      IdeBrowserSessionHandler handler,
      StudioPrincipal principal,
      UUID viewId,
      String cookieValue,
      int expectedStatus) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE)).thenReturn(principal);
    when(request.getCookies())
        .thenReturn(
            new Cookie[] {new Cookie(IdeGatewayConstants.SESSION_COOKIE_NAME, cookieValue)});
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.handleSession(ENV, viewId, request, response);

    verify(response).setStatus(expectedStatus);
  }
}
