package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

import java.util.UUID;

class WorkspaceHttpIntegrationTest extends WebPostgresTestSupport {
  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter filter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WebAccountMockMvcTestConfiguration.AdminCredentials admin;
  private MockMvc mvc;
  private Cookie login;

  @BeforeEach
  void configure() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    login = new Cookie("studio_session", admin.token());
  }

  /**
   * First command, public main and placement commit together; active work prevents deep deletion.
   */
  @Test
  void firstMessageProjectsTheRootAndBlocksUnsettledDelete() throws Exception {
    String space = createSpace();
    String folder = tree(space).at("/folders/0/id").asText();
    String body =
        """
        {"folderId":"%s","message":[{"type":"TEXT","text":"First message"}]}
        """
            .formatted(folder);
    String key = UUID.randomUUID().toString();
    var created =
        json(
            mvc.perform(write(post(space + "/sessions"), key, body))
                .andExpect(status().isOk())
                .andReturn());
    assertEquals(
        created,
        json(
            mvc.perform(write(post(space + "/sessions"), key, body))
                .andExpect(status().isOk())
                .andReturn()));
    String thread = created.get("threadId").asText();
    String session = created.get("sessionId").asText();
    mvc.perform(get(space + "/tree").cookie(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.treeVersion").value("1"))
        .andExpect(jsonPath("$.folders[0].resources[0].title").value("First message"))
        .andExpect(jsonPath("$.folders[0].resources[0].threads[0].id").value(thread))
        .andExpect(jsonPath("$.folders[0].resources[0].threads[0].title").value("main"));
    mvc.perform(get(space + "/threads/" + thread).cookie(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(session));
    mvc.perform(
            delete(space + "/sessions/" + session)
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedTreeVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("EXECUTION_NOT_SETTLED"));
    assertEquals(session, tree(space).at("/folders/0/resources/0/id").asText());
    mvc.perform(
            write(
                post(space + "/sessions/" + session + "/branches"),
                UUID.randomUUID().toString(),
                """
        {"sourceThreadId":"%s","turnEndEntryId":"%s","title":"Other"}
        """
                    .formatted(thread, UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("INVALID_TURN_END"));
    var placement =
        json(
            mvc.perform(get(space + "/threads/" + thread).cookie(login))
                .andExpect(status().isOk())
                .andReturn());
    mvc.perform(
            write(
                patch(space + "/threads/" + thread),
                key,
                """
        {"title":"Renamed","expectedVersion":"%s"}
        """
                    .formatted(placement.get("version").asText())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Renamed"));
    assertAgentInUse();
    mvc.perform(
            write(patch(space), key, "{\"expectedVersion\":\"0\",\"title\":\"Renamed workspace\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultAgentName").value("default-assistant"));
    mvc.perform(write(patch(space), key, "{\"expectedVersion\":\"1\",\"defaultAgentName\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultAgentName").doesNotExist());
    assertAgentInUse();
  }

  /**
   * Canvas deletion rolls back the edge on revision conflict; physical deletion lets the key start
   * over.
   */
  @Test
  void canvasReplaysAcrossRenameAndCanBeRecreatedAfterDelete() throws Exception {
    String space = createSpace();
    String folder = tree(space).at("/folders/0/id").asText();
    String key = UUID.randomUUID().toString();
    String template = "{\"folderId\":\"%s\",\"title\":\"%s\",\"expectedTreeVersion\":\"%s\"}";
    String initial = template.formatted(folder, "Board", "0");
    var result =
        json(
            mvc.perform(write(post(space + "/canvases"), key, initial))
                .andExpect(status().isOk())
                .andReturn());
    String id = result.get("id").asText();
    mvc.perform(write(patch(space + "/resources/CANVAS/" + id), key, "{\"title\":\"Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.treeVersion").value("1"));
    assertEquals(
        result,
        json(
            mvc.perform(write(post(space + "/canvases"), key, initial))
                .andExpect(status().isOk())
                .andReturn()));
    mvc.perform(write(post(space + "/canvases"), key, template.formatted(folder, "Wrong", "0")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(
            delete(space + "/canvases/" + id)
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedTreeVersion", "1")
                .param("expectedRevision", "10"))
        .andExpect(status().isConflict());
    assertEquals("Renamed", tree(space).at("/folders/0/resources/0/title").asText());
    mvc.perform(
            delete(space + "/canvases/" + id)
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedTreeVersion", "1")
                .param("expectedRevision", "0"))
        .andExpect(status().isOk());
    mvc.perform(write(post(space + "/canvases"), key, template.formatted(folder, "Recreated", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.treeVersion").value("3"));
  }

  /**
   * Failure during attachment preflight rolls back the creation, including its identity and tree
   * version.
   */
  @Test
  void rejectedAttachmentDoesNotLeaveASessionOrConsumeTheKey() throws Exception {
    String space = createSpace();
    assertAgentInUse();
    String folder = tree(space).at("/folders/0/id").asText();
    String key = UUID.randomUUID().toString();
    mvc.perform(
            write(
                post(space + "/sessions"),
                key,
                """
        {"folderId":"%s","message":[{"type":"ATTACHMENT","uploadId":"%s"}]}
        """
                    .formatted(folder, UUID.randomUUID())))
        .andExpect(status().isBadRequest());
    assertEquals("0", tree(space).get("treeVersion").asText());
    assertEquals(0, tree(space).at("/folders/0/resources").size());
    mvc.perform(
            write(
                post(space + "/sessions"),
                key,
                """
        {"folderId":"%s","message":[{"type":"TEXT","text":"Retry"}]}
        """
                    .formatted(folder)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.treeVersion").value("1"));
  }

  /**
   * Branches stay inside the existing resource; forks attach a new resource and replay the first
   * version.
   */
  @Test
  void branchAndForkFromARealTurnEnd() throws Exception {
    String space = createSpace();
    String folder = tree(space).at("/folders/0/id").asText();
    var created =
        json(
            mvc.perform(
                    write(
                        post(space + "/sessions"),
                        UUID.randomUUID().toString(),
                        """
        {"folderId":"%s","message":[{"type":"TEXT","text":"Source"}]}
        """
                            .formatted(folder)))
                .andExpect(status().isOk())
                .andReturn());
    UUID session = UUID.fromString(created.get("sessionId").asText());
    UUID thread = UUID.fromString(created.get("threadId").asText());
    UUID head =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", UUID.class, thread);
    var settings =
        new BranchSettings(
            "default-assistant",
            BuiltinExecution.of(ModelSelection.model("acceptance-stub", "default")),
            null);
    UUID start =
        insertEntry(session, head, new TurnStartPayload(TurnStartReason.INPUT, settings, thread));
    UUID cut =
        insertEntry(
            session,
            start,
            new TurnEndPayload(
                start, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null));
    jdbc.update("update harness_thread set head_entry_id = ? where id = ?", cut, thread);
    String body =
        "{\"sourceThreadId\":\"%s\",\"turnEndEntryId\":\"%s\",\"title\":\"Copy\"}"
            .formatted(thread, cut);
    String key = UUID.randomUUID().toString();
    String target = space + "/sessions/" + session;
    var branch =
        json(
            mvc.perform(write(post(target + "/branches"), key, body))
                .andExpect(status().isOk())
                .andReturn());
    assertEquals(
        branch,
        json(
            mvc.perform(write(post(target + "/branches"), key, body))
                .andExpect(status().isOk())
                .andReturn()));
    assertEquals("1", tree(space).get("treeVersion").asText());
    var fork =
        json(
            mvc.perform(write(post(target + "/forks"), key, body))
                .andExpect(status().isOk())
                .andReturn());
    assertEquals(
        fork,
        json(
            mvc.perform(write(post(target + "/forks"), key, body))
                .andExpect(status().isOk())
                .andReturn()));
    var updated = tree(space);
    assertEquals("2", updated.get("treeVersion").asText());
    assertEquals(fork.get("sessionId"), updated.at("/folders/0/resources/0/id"));
    assertEquals(2, updated.at("/folders/0/resources/1/threads").size());
    String branchId = branch.get("threadId").asText();
    var placement =
        json(
            mvc.perform(get(space + "/threads/" + branchId).cookie(login))
                .andExpect(status().isOk())
                .andReturn());
    mvc.perform(
            delete(space + "/threads/" + branchId)
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedTreeVersion", "2")
                .param("expectedVersion", placement.get("version").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.treeVersion").value("3"));
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

  private void assertAgentInUse() throws Exception {
    mvc.perform(
            delete("/api/platform/agents/default-assistant")
                .cookie(login)
                .header("Origin", "http://localhost")
                .param("expectedVersion", "0"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("RESOURCE_IN_USE"));
  }

  private String createSpace() throws Exception {
    var result =
        mvc.perform(
                write(
                    post("/api/chat-spaces"),
                    UUID.randomUUID().toString(),
                    """
        {"title":"Workspace","rootFolderName":"Root","defaultAgentName":"default-assistant"}
        """))
            .andExpect(status().isOk())
            .andReturn();
    return "/api/chat-spaces/" + json(result).get("id").asText();
  }

  private JsonNode tree(String space) throws Exception {
    return json(
        mvc.perform(get(space + "/tree").cookie(login)).andExpect(status().isOk()).andReturn());
  }

  private MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder request, String key, String body) {
    return request
        .cookie(login)
        .header("Origin", "http://localhost")
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .content(body);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString());
  }
}
