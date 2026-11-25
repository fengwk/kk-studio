package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;

import java.util.UUID;

/** End-to-end schedule HTTP coverage against the real PostgreSQL-backed workspace and agent. */
class WorkspaceScheduleHttpIntegrationTest extends WebPostgresTestSupport {
  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter filter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StudioAccountService accounts;
  @Autowired private WebAccountMockMvcTestConfiguration.AdminCredentials admin;
  private MockMvc mvc;
  private Cookie login;

  @BeforeEach
  void configure() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    login = new Cookie("studio_session", admin.token());
  }

  /**
   * A paused schedule accepts a manual NEW_SESSION run, replays its key, and persists real output.
   */
  @Test
  void pausedNewSessionRunReplaysIdempotentlyAndStoresOutput() throws Exception {
    String space = createSpace();
    String folder =
        mapper
            .readTree(
                mvc.perform(get(space + "/tree").cookie(login))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/folders/0/id")
            .asText();
    String body =
        """
        {"title":"Integration schedule","message":"Reply with exactly: scheduled integration output","enabled":false,
         "trigger":{"type":"ONCE","localDate":"2099-01-01","time":"12:00"},"timeZone":"UTC",
         "target":{"type":"NEW_SESSION","folderId":"%s","agentName":"default-assistant"}}
        """
            .formatted(folder);
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post(space + "/schedules")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String id = created.get("id").asText();
    assertEquals(false, created.get("enabled").asBoolean());
    String key = UUID.randomUUID().toString();
    JsonNode first = manualRun(space, id, key);
    assertEquals(first, manualRun(space, id, key));
    assertEquals("SUBMITTED", first.get("result").asText());
    String thread = first.at("/thread/id").asText();
    UUID threadId = UUID.fromString(thread);
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from harness_thread_command where thread_id=? and payload::text like '%scheduled integration output%'",
                Integer.class, threadId)
            == 1);
    mvc.perform(get(space + "/schedules/" + id + "/runs").cookie(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].result").value("SUBMITTED"));
  }

  private JsonNode manualRun(String space, String id, String key) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post(space + "/schedules/" + id + "/runs")
                    .cookie(login)
                    .header("Origin", "http://localhost")
                    .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String createSpace() throws Exception {
    JsonNode result =
        mapper.readTree(
            mvc.perform(
                    post("/api/chat-spaces")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            "{\"title\":\"Schedule workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return "/api/chat-spaces/" + result.get("id").asText();
  }
}
