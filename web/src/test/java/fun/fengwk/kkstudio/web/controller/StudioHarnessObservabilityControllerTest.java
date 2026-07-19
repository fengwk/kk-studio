package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.store.DatabaseArtifactStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskReport;
import fun.fengwk.kkstudio.harness.runtime.task.TaskResultFormatter;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkingCopyPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * HTTP contract for {@link StudioHarnessObservabilityController}: current session/thread routes
 * only, bigint ids as JSON strings, and 400/404 boundaries. Thread event SSE lives on
 * StudioHarnessThreadController and is intentionally out of scope here.
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioHarnessObservabilityControllerTest {

  /** Large Snowflake-range id to prove JSON string (not number) serialization. */
  private static final long LARGE_ID = 9_007_199_254_740_993L;

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 0, 0);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private HarnessSessionMapper sessionMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private DatabaseArtifactStore artifactStore;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from model_usage_record");
    jdbc.update("delete from tool_artifact");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_thread_input");
    jdbc.update("delete from harness_thread");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  /** Activities expose string Snowflake ids and reject bad cursors / unknown sessions. */
  @Test
  void readsRootActivitiesAsJsonWithStringIds() throws Exception {
    long rootSessionId = LARGE_ID;
    long threadId = LARGE_ID + 1;
    long eventId = LARGE_ID + 2;
    insertRootSession(rootSessionId, "activities");
    insertThread(threadId, rootSessionId);
    insertEvent(eventId, threadId, ThreadEventType.TURN_STARTED);

    mockMvc
        .perform(get("/api/sessions/{id}/activities", Long.toString(rootSessionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].rootSessionId").value(Long.toString(rootSessionId)))
        .andExpect(jsonPath("$.data[0].sessionId").value(Long.toString(rootSessionId)))
        .andExpect(jsonPath("$.data[0].threadId").value(Long.toString(threadId)))
        .andExpect(jsonPath("$.data[0].eventId").value(Long.toString(eventId)))
        .andExpect(jsonPath("$.data[0].eventType").value("turn_started"));

    mockMvc
        .perform(
            get("/api/sessions/{id}/activities", Long.toString(rootSessionId))
                .param("afterEventId", Long.toString(eventId))
                .param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    mockMvc
        .perform(
            get("/api/sessions/{id}/activities", Long.toString(rootSessionId))
                .param("afterEventId", "-1"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            get("/api/sessions/{id}/activities", Long.toString(rootSessionId))
                .param("limit", "9999"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/sessions/{id}/activities", "abc")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/sessions/{id}/activities", "9999999999"))
        .andExpect(status().isNotFound());
  }

  /** Thread tool-invocation list + get project persistent fields with string ids. */
  @Test
  void readsToolInvocationsAsJsonWithStringIds() throws Exception {
    long threadId = LARGE_ID + 10;
    long assistantEntryId = LARGE_ID + 11;
    long invocationId = LARGE_ID + 12;
    insertThread(threadId, LARGE_ID + 13);
    insertInvocation(invocationId, threadId, assistantEntryId, 0, "call-1", "write", "QUEUED");

    mockMvc
        .perform(get("/api/threads/{id}/tool-invocations", Long.toString(threadId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(Long.toString(invocationId)))
        .andExpect(jsonPath("$.data[0].threadId").value(Long.toString(threadId)))
        .andExpect(jsonPath("$.data[0].assistantEntryId").value(Long.toString(assistantEntryId)))
        .andExpect(jsonPath("$.data[0].ordinal").value(0))
        .andExpect(jsonPath("$.data[0].toolName").value("write"))
        .andExpect(jsonPath("$.data[0].targetType").value("CLOUD"))
        .andExpect(jsonPath("$.data[0].status").value("QUEUED"))
        .andExpect(jsonPath("$.data[0].argumentsJson").value("{\"path\":\"notes.txt\"}"));

    String firstInvocationId =
        objectMapper
            .readTree(
                mockMvc
                    .perform(get("/api/threads/{id}/tool-invocations", Long.toString(threadId)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data")
            .path(0)
            .path("id")
            .asText();
    assertTrue(firstInvocationId.matches("\\d+"));
    assertTrue(firstInvocationId.equals(Long.toString(invocationId)));

    mockMvc
        .perform(get("/api/tool-invocations/{id}", firstInvocationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(firstInvocationId))
        .andExpect(jsonPath("$.data.toolName").value("write"))
        .andExpect(jsonPath("$.data.deadlineAt").exists());

    mockMvc
        .perform(get("/api/threads/{id}/tool-invocations", "abc"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/tool-invocations/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/tool-invocations/{id}", "9999999999"))
        .andExpect(status().isNotFound());
  }

  /** Session tasks project parent/child Thread ids and nested report payload. */
  @Test
  void readsSessionTasksAsJson() throws Exception {
    long parentSessionId = LARGE_ID + 20;
    long parentThreadId = LARGE_ID + 21;
    long childSessionId = LARGE_ID + 22;
    long childThreadId = LARGE_ID + 23;
    long parentInvocationId = LARGE_ID + 24;
    insertRootSession(parentSessionId, "tasks");

    String reportJson =
        TaskResultFormatter.json(
            new TaskReport(
                childSessionId,
                childThreadId,
                TaskState.SUCCEEDED,
                "done",
                List.of(),
                2,
                1,
                WorkingCopyPolicy.FORK,
                "rev-1"));
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentSessionId);
    task.setParentThreadId(parentThreadId);
    task.setRootThreadId(parentThreadId);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("Child");
    task.setWorkingCopyPolicy(WorkingCopyPolicy.FORK.name());
    task.setWorkingCopyRevision("rev-1");
    task.setMaxTurns(3);
    task.setStatus(TaskState.SUCCEEDED.name());
    task.setReportJson(reportJson);
    task.setCreateTime(NOW);
    task.setUpdateTime(NOW.plusSeconds(1));
    taskMapper.insert(task);

    mockMvc
        .perform(get("/api/sessions/{id}/tasks", Long.toString(parentSessionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(
            jsonPath("$.data[0].parentInvocationId").value(Long.toString(parentInvocationId)))
        .andExpect(jsonPath("$.data[0].parentThreadId").value(Long.toString(parentThreadId)))
        .andExpect(jsonPath("$.data[0].childThreadId").value(Long.toString(childThreadId)))
        .andExpect(jsonPath("$.data[0].targetAgent").value("Child"))
        .andExpect(jsonPath("$.data[0].maxTurns").value(3))
        .andExpect(jsonPath("$.data[0].report.finalReport").value("done"))
        .andExpect(jsonPath("$.data[0].report.turnCount").value(2))
        .andExpect(jsonPath("$.data[0].report.childThreadId").value(Long.toString(childThreadId)));

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
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Security-Policy", "sandbox"))
            .andReturn();
    assertArrayEquals("hello".getBytes(), okResult.getResponse().getContentAsByteArray());

    mockMvc.perform(get("/api/artifacts/{id}", "0")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/artifacts/{id}", "-1")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/artifacts/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/artifacts/{id}", "9999999999")).andExpect(status().isNotFound());
  }

  /** Invalid legacy media metadata must not make raw artifact retrieval a server error. */
  @Test
  void fallsBackToOctetStreamForMalformedPersistedArtifactMediaType() throws Exception {
    long artifactId = LARGE_ID + 30;
    jdbc.update(
        "insert into tool_artifact (id, media_type, encoding, content, size_bytes, sha256,"
            + " gmt_create) values (?, ?, ?, ?, ?, ?, ?)",
        artifactId,
        "not a media type",
        "identity",
        new byte[] {1},
        1L,
        "digest",
        Timestamp.valueOf(NOW));

    mockMvc
        .perform(get("/api/artifacts/{id}", artifactId))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/octet-stream"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "sandbox"))
        .andExpect(content().bytes(new byte[] {1}));
  }

  // ---------------- helpers ----------------

  private void insertRootSession(long sessionId, String title) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(sessionId);
    session.setTitle(title);
    session.setMainThreadId(sessionId);
    session.setRootSessionId(sessionId);
    session.setDepth(0);
    session.setVersion(0L);
    session.setCreateTime(NOW);
    session.setUpdateTime(NOW);
    sessionMapper.insert(session);
  }

  private void insertThread(long threadId, long sessionId) {
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(threadId);
    thread.setSessionId(sessionId);
    thread.setHeadEntryId(1L);
    thread.setStatus("IDLE");
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(NOW);
    thread.setUpdateTime(NOW);
    threadMapper.insert(thread);
  }

  private void insertEvent(long eventId, long threadId, ThreadEventType type) {
    HarnessThreadEventDO event = new HarnessThreadEventDO();
    event.setId(eventId);
    event.setThreadId(threadId);
    event.setEventType(type.value());
    event.setPayloadJson("{\"schemaVersion\":1}");
    event.setCreateTime(NOW);
    eventMapper.insert(event);
  }

  private void insertInvocation(
      long id,
      long threadId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String status) {
    ToolInvocationDO row = new ToolInvocationDO();
    row.setId(id);
    row.setThreadId(threadId);
    row.setAssistantEntryId(assistantEntryId);
    row.setOrdinal(ordinal);
    row.setToolCallId(toolCallId);
    row.setToolName(toolName);
    row.setToolVersion("1");
    row.setTargetType("CLOUD");
    row.setArgumentsJson("{\"path\":\"notes.txt\"}");
    row.setStatus(status);
    row.setPermissionAction("ALLOW");
    row.setSideEffect("IDEMPOTENT");
    row.setDeadlineAt(NOW.plusHours(1));
    row.setCreateTime(NOW);
    row.setUpdateTime(NOW);
    invocationMapper.insert(row);
  }
}
