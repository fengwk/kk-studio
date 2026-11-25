package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
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
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.workspace.ChatSpaceService;

import java.util.UUID;

/** Verifies IDE attachment ownership at the authenticated HTTP boundary against PostgreSQL. */
class IdeAttachmentOwnershipHttpIntegrationTest extends WebPostgresTestSupport {

  private static final String PASSWORD = "ide-attachment-test-password";
  private static final UUID ENVIRONMENT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CURRENT_VIEW_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RESOURCE_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter accountFilter;
  @Autowired private StudioAccountService accounts;
  @Autowired private ChatSpaceService chatSpaces;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @MockitoBean private EnvironmentIdeService environmentIdeService;

  private MockMvc mvc;
  private Cookie ownerCookie;
  private UUID sessionOwnerId;

  @BeforeEach
  void configure() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(accountFilter).build();
    accounts.create("ide-attachment-view-owner", PASSWORD, StudioRole.USER);
    sessionOwnerId = accounts.create("ide-attachment-session-owner", PASSWORD, StudioRole.USER).id();
    ownerCookie =
        new Cookie(
            "studio_session", accounts.login("ide-attachment-view-owner", PASSWORD).token());
  }

  /** The current viewer's own view cannot be used to address another user's session. */
  @Test
  void rejectsOtherUsersSessionBeforeCallingIdeService() throws Exception {
    UUID foreignSessionId = createOwnedSession(sessionOwnerId, "foreign attachment session");
    String requestBody =
        "{\"browserSessionId\":\"44444444-4444-4444-8444-444444444444\","
            + "\"bindingEpoch\":\"1\",\"expectedDaemonInstanceId\":\"no-daemon-call\"}";

    var response =
        mvc.perform(
                post(
                        "/api/ide/environments/"
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
    verifyNoInteractions(environmentIdeService);
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
