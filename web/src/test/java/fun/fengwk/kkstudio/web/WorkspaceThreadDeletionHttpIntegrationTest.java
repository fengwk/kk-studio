package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** PostgreSQL-backed workspace thread deletion coverage. */
class WorkspaceThreadDeletionHttpIntegrationTest extends WebPostgresTestSupport {
  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter filter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private WebAccountMockMvcTestConfiguration.AdminCredentials admin;
  private MockMvc mvc;
  private Cookie login;

  @BeforeEach
  void configure() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    login = new Cookie("studio_session", admin.token());
  }

  /** A scheduled branch can be continued, then deleted without deleting its original run. */
  @Test
  void deletesBranchAfterContinuationOutputIsSettled() throws Exception {
    String space = createSpace();
    String folder = tree(space).at("/folders/0/id").asText();
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post(space + "/sessions")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            "{\"folderId\":\"%s\",\"message\":[{\"type\":\"TEXT\",\"text\":\"Source\"}]}"
                                .formatted(folder)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID session = UUID.fromString(created.get("sessionId").asText());
    UUID sourceThread = UUID.fromString(created.get("threadId").asText());
    UUID head =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", UUID.class, sourceThread);
    BranchSettings settings =
        new BranchSettings(
            "default-assistant",
            BuiltinExecution.of(ModelSelection.model("acceptance-stub", "default")),
            null);
    UUID start =
        insertEntry(
            session,
            head,
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, sourceThread));
    UUID end =
        insertEntry(
            session,
            start,
            new TurnEndPayload(
                start, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null));
    jdbc.update("update harness_thread set head_entry_id = ? where id = ?", end, sourceThread);

    JsonNode branchSchedule = createSchedule(space, "BRANCH", session, sourceThread, end, null);
    JsonNode branchRun = manualRun(space, branchSchedule.get("id").asText());
    assertEquals("SUBMITTED", branchRun.get("result").asText());
    String branchId = branchRun.at("/thread/id").asText();
    UUID branchThread = UUID.fromString(branchId);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            branchThread));
    jdbc.update(
        "delete from durable_work where target_type = 'THREAD' and target_id = ?", branchThread);

    JsonNode continueSchedule =
        createSchedule(space, "CONTINUE", session, null, null, branchThread);
    var busy = manualRun(space, continueSchedule.get("id").asText());
    assertEquals("SKIPPED", busy.get("result").asText());
    assertEquals("EXECUTION_NOT_SETTLED", busy.get("reason").asText());
    jdbc.update("delete from harness_thread_command where thread_id = ?", branchThread);
    UUID idleHead =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id=?", UUID.class, branchThread);
    UUID open =
        insertEntry(
            session,
            idleHead,
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, branchThread));
    jdbc.update("update harness_thread set head_entry_id=? where id=?", open, branchThread);
    assertEquals(
        "SKIPPED", manualRun(space, continueSchedule.get("id").asText()).get("result").asText());
    jdbc.update("update harness_thread set head_entry_id=? where id=?", idleHead, branchThread);
    JsonNode continueRun = manualRun(space, continueSchedule.get("id").asText());
    assertEquals("SUBMITTED", continueRun.get("result").asText());
    assertEquals(branchId, continueRun.at("/thread/id").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            branchThread));

    mvc.perform(
            delete(space + "/schedules/" + continueSchedule.get("id").asText())
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedVersion", continueSchedule.get("version").asText()))
        .andExpect(status().isOk());

    jdbc.update("delete from harness_thread_command where thread_id = ?", branchThread);
    jdbc.update(
        "delete from durable_work where target_type = 'THREAD' and target_id = ?", branchThread);

    JsonNode placement =
        mapper.readTree(
            mvc.perform(get(space + "/threads/" + branchId).cookie(login))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(
            delete(space + "/threads/" + branchId)
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedTreeVersion", tree(space).get("treeVersion").asText())
                .param("expectedVersion", placement.get("version").asText()))
        .andExpect(status().isOk());
    mvc.perform(get(space + "/threads/" + branchId).cookie(login)).andExpect(status().isNotFound());
    JsonNode persistedBranchRuns =
        mapper.readTree(
            mvc.perform(
                    get(space + "/schedules/" + branchSchedule.get("id").asText() + "/runs")
                        .cookie(login))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(1, persistedBranchRuns.at("/items").size());
    assertEquals(branchRun.get("id"), persistedBranchRuns.at("/items/0/id"));
    assertEquals("SUBMITTED", persistedBranchRuns.at("/items/0/result").asText());
    assertTrue(
        persistedBranchRuns.at("/items/0/thread").isMissingNode()
            || persistedBranchRuns.at("/items/0/thread").isNull());
    assertNull(
        jdbc.queryForObject(
            "select output_thread_id from workspace_schedule_run where id=?",
            UUID.class,
            UUID.fromString(branchRun.get("id").asText())));

    // Same derived title on a different branch reaches the genuine Harness name rejection.
    String schedule = branchSchedule.get("id").asText();
    UUID rejectedKey = UUID.randomUUID();
    UUID commandKey =
        UUID.nameUUIDFromBytes(
            (schedule + ":manual:" + rejectedKey).getBytes(StandardCharsets.UTF_8));
    mvc.perform(
            post(space + "/sessions/" + session + "/branches")
                .cookie(login)
                .header("Origin", "http://localhost")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    """
                {"sourceThreadId":"%s","turnEndEntryId":"%s","title":"Scheduled %s"}
                """
                        .formatted(sourceThread, end, commandKey)))
        .andExpect(status().isOk());
    var rejected = manualRun(space, schedule, rejectedKey);
    assertEquals("NOT_SUBMITTED", rejected.get("result").asText());
    assertEquals("THREAD_NAME_EXISTS", rejected.get("reason").asText());
    assertEquals(rejected, manualRun(space, schedule, rejectedKey));
  }

  private JsonNode createSchedule(
      String space, String type, UUID session, UUID sourceThread, UUID turnEnd, UUID thread)
      throws Exception {
    String target =
        switch (type) {
          case "BRANCH" -> "\"type\":\"BRANCH\",\"sessionId\":\"%s\",\"sourceThreadId\":\"%s\",\"turnEndEntryId\":\"%s\""
              .formatted(session, sourceThread, turnEnd);
          case "CONTINUE" -> "\"type\":\"CONTINUE\",\"sessionId\":\"%s\",\"threadId\":\"%s\""
              .formatted(session, thread);
          default -> throw new IllegalArgumentException(type);
        };
    String body =
        "{\"title\":\"%s schedule\",\"message\":\"Scheduled output\",\"enabled\":false,\"trigger\":{\"type\":\"ONCE\",\"localDate\":\"2099-01-01\",\"time\":\"12:00\"},\"timeZone\":\"UTC\",\"target\":{%s}}"
            .formatted(type, target);
    return mapper.readTree(
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
  }

  private JsonNode manualRun(String space, String id) throws Exception {
    return manualRun(space, id, UUID.randomUUID());
  }

  private JsonNode manualRun(String space, String id, UUID key) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post(space + "/schedules/" + id + "/runs")
                    .cookie(login)
                    .header("Origin", "http://localhost")
                    .header("Idempotency-Key", key.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode tree(String space) throws Exception {
    return mapper.readTree(
        mvc.perform(get(space + "/tree").cookie(login))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String createSpace() throws Exception {
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post("/api/chat-spaces")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            "{\"title\":\"Workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return "/api/chat-spaces/" + created.get("id").asText();
  }

  private UUID insertEntry(UUID session, UUID parent, EntryPayload payload) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at) values (?, ?, ?, ?, ?::jsonb, now())",
        id,
        session,
        parent,
        payload.type().name(),
        new HistoryEntryPayloadJsonCodec().encode(payload));
    return id;
  }
}
