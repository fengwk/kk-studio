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
import org.springframework.boot.test.web.server.LocalServerPort;
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
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class WorkspaceHttpIntegrationTest extends WebPostgresTestSupport {
  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter filter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StudioAccountService accounts;
  @LocalServerPort private int port;
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

  /** Ownership is checked at the real MVC boundary, including for other administrators. */
  @Test
  void harnessAndCanvasDoNotExposeAnotherUsersResources() throws Exception {
    String space = createSpace();
    String folder = tree(space).at("/folders/0/id").asText();
    var session =
        json(
            mvc.perform(
                    write(
                        post(space + "/sessions"),
                        UUID.randomUUID().toString(),
                        "{\"message\":[{\"type\":\"TEXT\",\"text\":\"Default folder\"}]}"))
                .andExpect(status().isOk())
                .andReturn());
    var canvas =
        json(
            mvc.perform(
                    write(
                        post(space + "/canvases"),
                        UUID.randomUUID().toString(),
                        "{\"folderId\":\"%s\",\"title\":\"Board\",\"expectedTreeVersion\":\"1\"}"
                            .formatted(folder)))
                .andExpect(status().isOk())
                .andReturn());
    String threadUrl = "/api/harness/threads/" + session.get("threadId").asText();
    String canvasUrl = "/api/canvas/canvases/" + canvas.get("id").asText();
    mvc.perform(get(threadUrl).cookie(login)).andExpect(status().isOk());
    mvc.perform(get(canvasUrl).cookie(login)).andExpect(status().isOk());
    accounts.create("other-admin", "workspace-test-account", StudioRole.ADMIN);
    Cookie outsider =
        new Cookie(
            "studio_session", accounts.login("other-admin", "workspace-test-account").token());
    for (String url : new String[] {threadUrl, threadUrl + "/tree", canvasUrl}) {
      mvc.perform(get(url).cookie(outsider))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.reason").value("RESOURCE_NOT_FOUND"));
    }
    assertEquals(session.get("sessionId"), tree(space).at("/folders/0/resources/1/id"));
    try (HttpClient client = HttpClient.newHttpClient()) {
      var ownerFrames = new SocketFrames();
      var outsiderFrames = new SocketFrames();
      var ownerSocket = connect(client, login, ownerFrames);
      var outsiderSocket = connect(client, outsider, outsiderFrames);
      try {
        assertEquals("connected", ownerFrames.next("connected").get("type").asText());
        assertEquals("connected", outsiderFrames.next("connected").get("type").asText());
        for (var resource :
            new String[][] {
              {"thread", session.get("threadId").asText()},
              {"thread-tree", session.get("threadId").asText()},
              {"canvas", canvas.get("id").asText()},
              {"chat-space", space.substring(space.lastIndexOf('/') + 1)}
            }) {
          String subscribe =
              "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"%s\",\"id\":\"%s\"}}"
                  .formatted(resource[0], resource[1]);
          ownerSocket.sendText(subscribe, true).join();
          outsiderSocket.sendText(subscribe, true).join();
          assertEquals(resource[0], ownerFrames.next("subscribed").at("/resource/kind").asText());
          assertEquals("RESOURCE_NOT_FOUND", outsiderFrames.next("error").get("code").asText());
        }
        String key = UUID.randomUUID().toString();
        mvc.perform(
                write(
                    patch(space), key, "{\"expectedVersion\":\"0\",\"title\":\"New space title\"}"))
            .andExpect(status().isOk());
        assertEquals("1", ownerFrames.next("event", "space").at("/data/version").asText());
        mvc.perform(
                write(patch(space + "/folders/" + folder), key, "{\"name\":\"New folder title\"}"))
            .andExpect(status().isOk());
        assertEquals("2", ownerFrames.next("event", "tree").at("/data/treeVersion").asText());
        mvc.perform(
                write(
                    post(space + "/sessions"),
                    UUID.randomUUID().toString(),
                    "{\"message\":[{\"type\":\"TEXT\",\"text\":\"Trigger thread hint\"}]}"))
            .andExpect(status().isOk());
        assertEquals(0, ownerFrames.next("event", "threads").get("data").size());
      } finally {
        ownerSocket.abort();
        outsiderSocket.abort();
      }
    }
  }

  private WebSocket connect(HttpClient client, Cookie cookie, SocketFrames frames) {
    return client
        .newWebSocketBuilder()
        .header("Origin", "http://localhost:" + port)
        .header("Cookie", cookie.getName() + "=" + cookie.getValue())
        .buildAsync(URI.create("ws://localhost:" + port + "/ws/events"), frames)
        .join();
  }

  private final class SocketFrames implements WebSocket.Listener {
    private final LinkedBlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    private final StringBuilder pending = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      pending.append(data);
      if (last) {
        frames.add(mapper.readTree(pending.toString()));
        pending.setLength(0);
      }
      socket.request(1);
      return null;
    }

    JsonNode next(String type) throws InterruptedException {
      return next(type, null);
    }

    JsonNode next(String type, String name) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (frame != null
            && type.equals(frame.get("type").asText())
            && (name == null || name.equals(frame.path("name").asText()))) {
          return frame;
        }
      }
      throw new AssertionError("Missing " + type + " frame");
    }
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
