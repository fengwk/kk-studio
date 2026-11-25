package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.workspace.ChatSpaceService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Verifies IDE attachment ownership at the authenticated HTTP boundary against PostgreSQL. */
class IdeAttachmentOwnershipHttpIntegrationTest extends WebPostgresTestSupport {

  private static final String PASSWORD = "ide-attachment-test-password";
  private static final UUID ENVIRONMENT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CURRENT_VIEW_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID BROWSER_SESSION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID EVENT_NODE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CONNECTION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID CLAIM_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID RESOURCE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String DAEMON_INSTANCE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter accountFilter;
  @Autowired private StudioAccountService accounts;
  @Autowired private ChatSpaceService chatSpaces;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @MockitoBean private IdeCommandTransport ideCommandTransport;
  @MockitoBean private IdeBrowserUseGateway browserUseGateway;
  @MockitoBean private JdbcClusterNodeRegistry clusterNodeRegistry;

  private MockMvc mvc;
  private Cookie ownerCookie;
  private UUID sessionOwnerId;

  @BeforeEach
  void configure() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(accountFilter).build();
    accounts.create("ide-attachment-view-owner", PASSWORD, StudioRole.USER);
    sessionOwnerId =
        accounts.create("ide-attachment-session-owner", PASSWORD, StudioRole.USER).id();
    ownerCookie = loginOverHttp("ide-attachment-view-owner");
    when(clusterNodeRegistry.route(EnvironmentId.of(ENVIRONMENT_ID)))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));
    when(ideCommandTransport.connectionIdentity(EnvironmentId.of(ENVIRONMENT_ID)))
        .thenReturn(Optional.of(daemonIdentity()));
    when(ideCommandTransport.current(EnvironmentId.of(ENVIRONMENT_ID)))
        .thenReturn(Optional.of(daemonIdentity()));
    when(browserUseGateway.findExactIntent(
            any(), any(), any(), eq(EnvironmentId.of(ENVIRONMENT_ID)), any(), any(), any()))
        .thenReturn(true);
    doAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              IdeResult reply =
                  command.getAction() == IdeAction.LIST_VIEWS
                      ? snapshot(1L)
                      : snapshot(2L, openedView(command));
              return new IdeRequest(
                  daemonIdentity(), UUID.randomUUID(), CompletableFuture.completedFuture(reply));
            })
        .when(ideCommandTransport)
        .requestIde(eq(EnvironmentId.of(ENVIRONMENT_ID)), any());
  }

  /** A real own-view binding cannot be used to address another user's session. */
  @Test
  void rejectsOtherUsersSessionBeforeAnyAdditionalDaemonCommand() throws Exception {
    JsonNode openedView = openCurrentViewOverHttp();
    UUID foreignSessionId = createOwnedSession(sessionOwnerId, "foreign attachment session");
    String requestBody =
        "{\"browserSessionId\":\""
            + BROWSER_SESSION_ID
            + "\",\"bindingEpoch\":\""
            + openedView.path("bindingEpoch").asText()
            + "\",\"expectedDaemonInstanceId\":\""
            + DAEMON_INSTANCE_ID
            + "\"}";

    var response =
        mvc.perform(
                post("/api/ide/environments/"
                        + ENVIRONMENT_ID
                        + "/views/"
                        + CURRENT_VIEW_ID
                        + "/attachments/"
                        + foreignSessionId
                        + "/"
                        + RESOURCE_ID)
                    .cookie(ownerCookie)
                    .header("Origin", "http://localhost")
                    .contentType("application/json")
                    .content(requestBody))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse();

    assertEquals(
        "RESOURCE_NOT_FOUND",
        mapper.readTree(response.getContentAsString()).path("reason").asText());
    verify(ideCommandTransport, times(2)).requestIde(eq(EnvironmentId.of(ENVIRONMENT_ID)), any());
    verify(browserUseGateway, times(1))
        .findExactIntent(
            any(), any(), any(), eq(EnvironmentId.of(ENVIRONMENT_ID)), any(), any(), any());
  }

  private JsonNode openCurrentViewOverHttp() throws Exception {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(CURRENT_VIEW_ID.toString());
    request.setBrowserSessionId(BROWSER_SESSION_ID.toString());
    request.setEventNodeId(EVENT_NODE_ID.toString());
    request.setConnectionId(CONNECTION_ID.toString());
    request.setClaimId(CLAIM_ID.toString());
    request.setMode(EnvironmentIdeOpenMode.CREATE);
    request.setExpectedBindingEpoch(0L);
    request.setExpectedDaemonInstanceId(DAEMON_INSTANCE_ID);
    request.setDirectory("/workspace");
    var response =
        mvc.perform(
                post("/api/ide/environments/" + ENVIRONMENT_ID + "/views")
                    .cookie(ownerCookie)
                    .header("Origin", "http://localhost")
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(request)))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    JsonNode opened = mapper.readTree(response.getContentAsString());
    assertEquals(CURRENT_VIEW_ID.toString(), opened.path("viewId").asText());
    assertEquals(1, opened.path("bindingEpoch").asLong());
    return opened;
  }

  private DaemonConnectionIdentity daemonIdentity() {
    return new DaemonConnectionIdentity(EnvironmentId.of(ENVIRONMENT_ID), DAEMON_INSTANCE_ID);
  }

  private static IdeResult snapshot(long revision, IdeViewSummary... views) {
    return IdeResult.newBuilder()
        .setOk(true)
        .setRevision(revision)
        .addAllViews(List.of(views))
        .build();
  }

  private static IdeViewSummary openedView(IdeCommand command) {
    return IdeViewSummary.newBuilder()
        .setViewId(command.getViewId())
        .setBrowserSessionId(command.getBrowserSessionId())
        .setEventNodeId(command.getEventNodeId())
        .setConnectionId(command.getConnectionId())
        .setClaimId(command.getClaimId())
        .setBindingEpoch(1L)
        .setDirectory("/workspace")
        .setWorkspacePath("/daemon/managed.code-workspace")
        .setState(IdeViewState.OPEN)
        .build();
  }

  private Cookie loginOverHttp(String username) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getCookie("studio_session");
  }

  private UUID createOwnedSession(UUID userId, String title) {
    UUID sessionId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        title);
    var space =
        chatSpaces.create(
            new StudioPrincipal(userId, "ide-attachment-session-owner", StudioRole.USER),
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
    return sessionId;
  }
}
