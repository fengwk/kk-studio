package fun.fengwk.kkstudio.web.controller;

import static fun.fengwk.kkstudio.web.HarnessUsageFixtures.toolCallsUsageDraft;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseArtifactStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import fun.fengwk.kkstudio.web.WebTestApplication;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T15 observability HTTP contract coverage: strict 400/404 semantics, JSON contract for bigint IDs,
 * SSE event names + IDs, query cursor taking precedence over {@code Last-Event-ID}, and raw
 * artifact bytes. SSE tests rely only on events persisted in the H2 store; no in-memory event bus
 * is used.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WebTestApplication.class,
    properties = "kk-studio.harness.runtime.workers-enabled=false")
class StudioHarnessObservabilityControllerTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private SnowflakeRunIdGenerator runIds;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort preparationPort;
  @Autowired private DatabaseArtifactStore artifactStore;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from model_usage_record");
    jdbc.update("delete from tool_artifact");
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  /** Run event / activity JSON contracts use string ids; cursor / limit validation hits 400. */
  @Test
  void readsRunEventsAndActivitiesAsJsonWithStringIds() throws Exception {
    Seed seed = seedRun("run-events");

    mockMvc
        .perform(get("/api/runs/{id}/events", seed.run.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].runId").value(Long.toString(seed.run.id())))
        .andExpect(jsonPath("$.data[0].sequence").value(1))
        .andExpect(jsonPath("$.data[0].eventId").exists())
        .andExpect(jsonPath("$.data[0].type").value("turn_started"));

    mockMvc
        .perform(
            get("/api/runs/{id}/events", seed.run.id())
                .param("afterSequence", "1")
                .param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].sequence").value(2));

    mockMvc
        .perform(get("/api/runs/{id}/events", seed.run.id()).param("afterSequence", "-1"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/runs/{id}/events", seed.run.id()).param("limit", "9999"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/runs/{id}/events", "abc")).andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/runs/{id}/events", "9999999999")).andExpect(status().isNotFound());

    // Root activity list resolves the rootSessionId and paginates by eventId.
    mockMvc
        .perform(get("/api/sessions/{id}/activities", seed.rootSessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].rootSessionId").value(Long.toString(seed.rootSessionId)))
        .andExpect(jsonPath("$.data[0].runId").value(Long.toString(seed.run.id())))
        .andExpect(jsonPath("$.data[0].type").value("turn_started"));

    mockMvc
        .perform(get("/api/sessions/{id}/activities", seed.rootSessionId).param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));

    mockMvc.perform(get("/api/sessions/{id}/activities", "abc")).andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/sessions/{id}/activities", "9999999999"))
        .andExpect(status().isNotFound());
  }

  /** Tool invocation + session task projections must surface all persistent fields. */
  @Test
  void readsToolInvocationAndSessionTasksAsJson() throws Exception {
    Seed seed = seedToolRun();

    mockMvc
        .perform(get("/api/runs/{id}/tool-invocations", seed.run.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].toolName").value("write"))
        .andExpect(jsonPath("$.data[0].targetType").value("CLOUD"))
        .andExpect(jsonPath("$.data[0].status").value("QUEUED"))
        .andExpect(jsonPath("$.data[0].argumentsJson").value("{\"path\":\"notes.txt\"}"))
        .andExpect(jsonPath("$.data[0].runId").value(Long.toString(seed.run.id())));

    String firstInvocationId =
        objectMapper
            .readTree(
                mockMvc
                    .perform(get("/api/runs/{id}/tool-invocations", seed.run.id()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data")
            .path(0)
            .path("id")
            .asText();
    assertTrue(firstInvocationId.matches("\\d+"));

    mockMvc
        .perform(get("/api/tool-invocations/{id}", firstInvocationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(firstInvocationId))
        .andExpect(jsonPath("$.data.toolName").value("write"))
        .andExpect(jsonPath("$.data.deadlineAt").exists());

    mockMvc.perform(get("/api/tool-invocations/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/tool-invocations/{id}", "9999999999"))
        .andExpect(status().isNotFound());

    long parentInvocation = runIds.newRunEventId();
    long childSession = sessionIds.newSessionId();
    long childRun = runIds.newRunId();
    jdbc.update(
        "insert into harness_subagent_task"
            + " (parent_invocation_id, parent_session_id, child_session_id, child_run_id,"
            + " target_agent, working_copy_policy, working_copy_revision, max_turns,"
            + " idle_timeout_millis, status, report_json, gmt_create, gmt_modified) values"
            + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        parentInvocation,
        seed.rootSessionId,
        childSession,
        childRun,
        "Child",
        "FORK",
        "rev-1",
        3,
        15000L,
        "SUCCEEDED",
        "{\"finalReport\":\"done\",\"turnCount\":2}",
        Timestamp.from(NOW),
        Timestamp.from(NOW.plusSeconds(1)));

    mockMvc
        .perform(get("/api/sessions/{id}/tasks", seed.rootSessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].parentInvocationId").value(Long.toString(parentInvocation)))
        .andExpect(jsonPath("$.data[0].targetAgent").value("Child"))
        .andExpect(jsonPath("$.data[0].maxTurns").value(3))
        .andExpect(jsonPath("$.data[0].report.finalReport").value("done"))
        .andExpect(jsonPath("$.data[0].report.turnCount").value(2));

    mockMvc.perform(get("/api/sessions/{id}/tasks", "9999999999")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/sessions/{id}/tasks", "abc")).andExpect(status().isBadRequest());
  }

  /** Artifact GET returns raw bytes; malformed ids are 400; unknown ids are 404. */
  @Test
  void readsArtifactBytesAndDistinguishesBadRequestFromNotFound() throws Exception {
    var ref = artifactStore.save("text/plain", "raw", "hello".getBytes());

    MvcResult okResult =
        mockMvc
            .perform(get("/api/artifacts/{id}", ref.artifactId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/plain"))
            .andReturn();
    assertArrayEquals("hello".getBytes(), okResult.getResponse().getContentAsByteArray());

    mockMvc.perform(get("/api/artifacts/{id}", "0")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/artifacts/{id}", "-1")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/artifacts/{id}", "abc")).andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/artifacts/{id}", "9999999999")).andExpect(status().isNotFound());
  }

  /** SSE delivers persisted run events with name=run_event and id=sequence decimal. */
  @Test
  void runEventStreamEmitsPersistedEventsWithSequenceDecimalId() throws Exception {
    Seed seed = seedRun("sse-run");
    // The run is still QUEUED — terminal check requires the run to be terminal AND idle to elapse.
    // To deterministically close the stream we seed the run as terminal.
    forceRunTerminal(seed.run.id());

    MvcResult streamResult =
        mockMvc
            .perform(
                get("/api/runs/{id}/events/stream", seed.run.id())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .param("idleTimeoutMillis", "60"))
            .andExpect(request().asyncStarted())
            .andReturn();

    MvcResult dispatched =
        mockMvc
            .perform(asyncDispatch(streamResult))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

    String body = dispatched.getResponse().getContentAsString();
    // Two persisted events are emitted with name=run_event and id=sequence decimal.
    assertTrue(body.contains("event:run_event"), body);
    assertTrue(body.contains("id:1"), body);
    assertTrue(body.contains("id:2"), body);
    // The SSE event id is the sequence decimal, while the DTO eventId (inside data) is the
    // Snowflake id — they are different. Assert both exist in the SSE body.
    assertTrue(body.contains("\"eventId\":"), body);
    assertTrue(body.matches("(?s).*\"sequence\":\"?1\"?.*"), body);
    assertTrue(body.matches("(?s).*\"sequence\":\"?2\"?.*"), body);
    // Run is terminal and idle — heartbeat still permitted but close should follow.
    // The exact body does not need to assert heartbeat because the close happens before the second
    // tick.
    assertNotNull(body);
  }

  /** Activity SSE emits persisted root activity with name=root_activity and id=eventId decimal. */
  @Test
  void rootActivityStreamEmitsPersistedEventsWithEventIdId() throws Exception {
    Seed seed = seedRun("sse-activity");
    forceRootInactive(seed.rootSessionId);

    MvcResult streamResult =
        mockMvc
            .perform(
                get("/api/sessions/{id}/activities/stream", seed.rootSessionId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .param("idleTimeoutMillis", "60"))
            .andExpect(request().asyncStarted())
            .andReturn();

    MvcResult dispatched =
        mockMvc
            .perform(asyncDispatch(streamResult))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

    String body = dispatched.getResponse().getContentAsString();
    assertTrue(body.contains("event:root_activity"), body);
    // Each persisted event id is the global harness_run_event.id (Snowflake), echoed as decimal.
    assertTrue(body.matches("(?s).*id:\\d+.*"), body);
    // Run-tree activity uses rootSessionId, not sessionId.
    assertTrue(body.contains("\"rootSessionId\":\"" + seed.rootSessionId + "\""), body);
  }

  /** Query cursor takes precedence over Last-Event-ID; query=2 emits only sequence 2. */
  @Test
  void queryCursorTakesPrecedenceOverLastEventId() throws Exception {
    Seed seed = seedRun("sse-cursor");
    forceRootInactive(seed.rootSessionId);

    // The SSE event id for root activity is the global harness_run_event Snowflake id; the SSE
    // cursor value is therefore a Snowflake id, while the persisted sequence is 1, 2, ... so we
    // capture both first and second event ids and pass the first as the cursor to demonstrate that
    // the query parameter overrides the Last-Event-ID header.
    long firstEventId =
        jdbc.queryForObject(
            "select id from harness_run_event where run_id = ? order by sequence asc limit 1",
            Long.class,
            seed.run.id());
    long secondEventId =
        jdbc.queryForObject(
            "select id from harness_run_event where run_id = ? and sequence = ("
                + "select min(sequence) from harness_run_event where run_id = ? and sequence > 1)",
            Long.class,
            seed.run.id(),
            seed.run.id());
    assertTrue(secondEventId > firstEventId);

    // With query cursor == first event id, the stream emits nothing because both Run events sit at
    // id > firstEventId but the query parameter is honoured and skips past the first event when the
    // SSE event id matches a previously-seen cursor value.
    MvcResult streamResult =
        mockMvc
            .perform(
                get("/api/sessions/{id}/activities/stream", seed.rootSessionId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Last-Event-ID", "0")
                    .param("afterEventId", Long.toString(firstEventId))
                    .param("idleTimeoutMillis", "60"))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult dispatched =
        mockMvc.perform(asyncDispatch(streamResult)).andExpect(status().isOk()).andReturn();
    String afterFirst = dispatched.getResponse().getContentAsString();
    assertTrue(afterFirst.contains("event:root_activity"), afterFirst);
    // The first event id must NOT be re-emitted because the query cursor advanced past it.
    assertEquals(0, countSubstring(afterFirst, "id:" + firstEventId));
    // The second event id must be emitted.
    assertTrue(afterFirst.contains("id:" + secondEventId), afterFirst);

    // Now flip the precedence: provide a query cursor of 0 (from-scratch) but a Last-Event-ID
    // header that points at the first event. The query cursor (0) must win and both events must
    // be emitted. This proves query > header precedence: if header won, the first event would be
    // suppressed.
    MvcResult streamResult2 =
        mockMvc
            .perform(
                get("/api/sessions/{id}/activities/stream", seed.rootSessionId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Last-Event-ID", Long.toString(firstEventId))
                    .param("afterEventId", "0")
                    .param("idleTimeoutMillis", "60"))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult dispatched2 =
        mockMvc.perform(asyncDispatch(streamResult2)).andExpect(status().isOk()).andReturn();
    String fromZero = dispatched2.getResponse().getContentAsString();
    assertTrue(fromZero.contains("id:" + firstEventId), fromZero);
    assertTrue(fromZero.contains("id:" + secondEventId), fromZero);
  }

  /**
   * {@code Last-Event-ID} alone (no query param) must resume the Run SSE cursor: header=1 emits
   * only sequence 2.
   */
  @Test
  void lastEventIdAloneResumesRunStreamFromCursor() throws Exception {
    Seed seed = seedRun("sse-last-event");
    forceRunTerminal(seed.run.id());

    MvcResult streamResult =
        mockMvc
            .perform(
                get("/api/runs/{id}/events/stream", seed.run.id())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Last-Event-ID", "1")
                    .param("idleTimeoutMillis", "60"))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult dispatched =
        mockMvc.perform(asyncDispatch(streamResult)).andExpect(status().isOk()).andReturn();
    String body = dispatched.getResponse().getContentAsString();
    // Sequence 1 must NOT be re-emitted because Last-Event-ID = 1 advances past it.
    assertEquals(0, countSubstring(body, "id:1"));
    // Sequence 2 must be emitted.
    assertTrue(body.contains("id:2"), body);
  }

  // ---------------- helpers ----------------

  private static int countSubstring(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }

  private Seed seedRun(String title) {
    long rootSessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(rootSessionId, null, title, false, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, rootSessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    AgentRun queued =
        transactions.submitUserMessage(rootSessionId, snapshotId, user("hi"), NOW.plusMillis(1));
    AgentRun claimed =
        runStore
            .claimDue("worker-" + rootSessionId, NOW.plusMillis(1), Duration.ofMinutes(1))
            .orElseThrow();
    runStore.append(
        claimed.id(),
        RunEventType.TURN_STARTED,
        "{\"schemaVersion\":1,\"ordinal\":1}",
        NOW.plusSeconds(2));
    runStore.append(
        claimed.id(),
        RunEventType.TURN_STARTED,
        "{\"schemaVersion\":1,\"ordinal\":2}",
        NOW.plusSeconds(3));
    return new Seed(rootSessionId, claimed);
  }

  private Seed seedToolRun() {
    long rootSessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(rootSessionId, null, "tool-run", false, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, rootSessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    AgentRun queued =
        transactions.submitUserMessage(rootSessionId, snapshotId, user("go"), NOW.plusMillis(1));
    AgentRun claimed =
        runStore
            .claimDue("worker-tool-" + rootSessionId, NOW.plusMillis(1), Duration.ofMinutes(1))
            .orElseThrow();
    ToolCall call = new ToolCall("call-1", "write", "{\"path\":\"notes.txt\"}");
    preparationPort.prepare(
        claimed,
        assistant(List.of(call)),
        toolCallsUsageDraft(),
        List.of(call),
        List.of(binding()),
        Path.of("/tmp/environment"),
        Path.of("/tmp/environment"),
        List.of(
            new RunEventDraft(
                RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(claimed))),
        NOW.plusSeconds(1));
    return new Seed(rootSessionId, claimed);
  }

  private void forceRunTerminal(long runId) {
    // Force the run into a terminal state and clear its lease so the SSE can close after idle and
    // MysqlHarnessRunStore#find can deserialize the snapshot without violating the AgentRun
    // invariants.
    jdbc.update(
        "update harness_run set status = 'SUCCEEDED', finished_at = ?, lease_owner = null,"
            + " lease_until = null where id = ?",
        Timestamp.from(NOW.plusSeconds(10)),
        runId);
  }

  private void forceRootInactive(long rootSessionId) {
    jdbc.update(
        "update harness_session set active_run_id = null where root_session_id = ?", rootSessionId);
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static MessageEntryPayload assistant(List<ToolCall> calls) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent("calling"));
    calls.forEach(
        call ->
            contents.add(
                new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson())));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            ProviderStopReason.TOOL_CALLS,
            new ModelUsage(1, 1, 0, 0, 0, 0, 2),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)));
  }

  private static ToolBinding binding() {
    return ToolBinding.of(
        new ToolDescriptor(
            "write",
            "1",
            "write",
            null,
            new ToolParamsSchema(
                "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.IDEMPOTENT,
            Duration.ofSeconds(30)));
  }

  private record Seed(long rootSessionId, AgentRun run) {}
}
