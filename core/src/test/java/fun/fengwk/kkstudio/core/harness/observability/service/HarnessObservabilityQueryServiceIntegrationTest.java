package fun.fengwk.kkstudio.core.harness.observability.service;

import static fun.fengwk.kkstudio.core.harness.HarnessUsageFixtures.toolCallsUsageDraft;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProperties;
import fun.fengwk.kkstudio.core.harness.tool.store.DatabaseArtifactStore;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
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
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskReportDTO;
import fun.fengwk.kkstudio.share.model.ToolArtifactRefDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration coverage for the observability query service: Run event sequence cursor paging, root
 * activity eventId cursor + child-session root resolution, tool invocation projection (with the new
 * persistent fields), subagent task projection including the report payload, and artifact bytes
 * round-trip.
 */
@SpringBootTest(classes = CoreTestApplication.class)
class HarnessObservabilityQueryServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  @Autowired private HarnessObservabilityQueryServiceImpl service;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort preparationPort;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private SnowflakeRunIdGenerator runIds;
  @Autowired private DatabaseArtifactStore artifactStore;
  @Autowired private ToolSettingsProperties toolSettingsProperties;
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

  /**
   * Run events page in sequence order; cursor must be exclusive of the previous sequence. Run event
   * DTO eventId is the Snowflake id, which differs from the SSE event id (sequence decimal).
   */
  @Test
  void pagesRunEventsBySequenceCursor() {
    SessionFixture fixture = seedRoot("run-events", false);
    AgentRun run =
        transactions.submitUserMessage(fixture.sessionId, fixture.snapshotId, user("hi"), NOW);
    for (int i = 1; i <= 5; i++) {
      runStore.append(
          run.id(),
          RunEventType.TURN_STARTED,
          "{\"schemaVersion\":1,\"ordinal\":" + i + "}",
          NOW.plusMillis(i));
    }

    List<RunEventDTO> page1 = service.listRunEvents(Long.toString(run.id()), 0, 2);
    assertEquals(List.of(1L, 2L), page1.stream().map(RunEventDTO::getSequence).toList());
    // RunEventDTO.eventId is the global Snowflake id (source.id()), never the sequence.
    assertEquals(Long.toString(run.id()), page1.get(0).getRunId());
    // The Snowflake id must be a positive number distinct from the Run-event sequence (1, 2, ...).
    long snowflakeId = Long.parseLong(page1.get(0).getEventId());
    assertTrue(snowflakeId > 1000, "eventId should be a Snowflake value: " + snowflakeId);
    assertTrue(snowflakeId != page1.get(0).getSequence(), "eventId != sequence");
    assertEquals("turn_started", page1.get(0).getType());

    List<RunEventDTO> page2 = service.listRunEvents(Long.toString(run.id()), 2, 10);
    assertEquals(List.of(3L, 4L, 5L), page2.stream().map(RunEventDTO::getSequence).toList());

    // Run events must serialize all Snowflake bigints as JSON strings.
    assertEquals(Long.toString(run.id()), page2.get(0).getRunId());
  }

  /** Descendant session ids must resolve through rootSessionId before paging activity. */
  @Test
  void pagesRootActivityForChildSessionThroughRootResolution() {
    SessionFixture root = seedRoot("root", false);
    SessionFixture child = seedChild(root.sessionId, "child");

    AgentRun rootRun =
        transactions.submitUserMessage(root.sessionId, root.snapshotId, user("root"), NOW);
    AgentRun childRun =
        transactions.submitUserMessage(child.sessionId, child.snapshotId, user("child"), NOW);
    runStore.append(
        rootRun.id(),
        RunEventType.TURN_STARTED,
        "{\"schemaVersion\":1,\"k\":\"root\"}",
        NOW.plusMillis(1));
    runStore.append(
        childRun.id(),
        RunEventType.TURN_STARTED,
        "{\"schemaVersion\":1,\"k\":\"child\"}",
        NOW.plusMillis(2));

    // Querying via the child session id must still see root-tree events.
    List<RootActivityDTO> activity =
        service.listRootActivities(Long.toString(child.sessionId), 0, 10);
    assertEquals(2, activity.size());
    assertEquals(Long.toString(root.sessionId), activity.get(0).getRootSessionId());
    assertEquals(Long.toString(root.sessionId), activity.get(1).getRootSessionId());

    long cursor = Long.parseLong(activity.get(0).getEventId());
    List<RootActivityDTO> tail =
        service.listRootActivities(Long.toString(child.sessionId), cursor, 10);
    assertEquals(1, tail.size());
    assertTrue(Long.parseLong(tail.get(0).getEventId()) > cursor);
    assertEquals(Long.toString(child.sessionId), tail.get(0).getSessionId());

    // Sibling root trees must not leak activity into the current root.
    SessionFixture otherRoot = seedRoot("other", false);
    AgentRun otherRun =
        transactions.submitUserMessage(
            otherRoot.sessionId, otherRoot.snapshotId, user("other"), NOW);
    runStore.append(
        otherRun.id(),
        RunEventType.TURN_STARTED,
        "{\"schemaVersion\":1,\"k\":\"other\"}",
        NOW.plusMillis(3));
    assertTrue(
        service.listRootActivities(Long.toString(child.sessionId), 0, 50).stream()
            .allMatch(e -> Long.parseLong(e.getRootSessionId()) == root.sessionId));
  }

  /** Tool invocation projection must carry every persistent field and survive JSON parsing. */
  @Test
  void projectsToolInvocationWithAllPersistentFields() {
    SessionFixture fixture = seedRoot("tool", false);
    AgentRun queued =
        transactions.submitUserMessage(fixture.sessionId, fixture.snapshotId, user("go"), NOW);
    AgentRun claimed =
        runStore.claimDue("worker-tool", NOW.plusMillis(1), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(queued.id(), claimed.id());

    preparationPort.prepare(
        claimed,
        assistantMessage(List.of(new ToolCall("call-1", "write", "{\"path\":\"notes.txt\"}"))),
        toolCallsUsageDraft(),
        List.of(new ToolCall("call-1", "write", "{\"path\":\"notes.txt\"}")),
        List.of(cloudBinding("write")),
        Path.of("/tmp/environment"),
        Path.of("/tmp/environment"),
        List.of(
            new RunEventDraft(
                RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(claimed))),
        NOW.plusSeconds(1));
    ToolInvocation stored = invocationStore.listByRun(queued.id()).get(0);

    List<ToolInvocationDTO> projected = service.listToolInvocations(Long.toString(queued.id()));
    assertEquals(1, projected.size());
    ToolInvocationDTO dto = projected.get(0);
    assertEquals(Long.toString(stored.id()), dto.getId());
    assertEquals(Long.toString(stored.runId()), dto.getRunId());
    assertEquals(Long.toString(stored.assistantEntryId()), dto.getAssistantEntryId());
    assertEquals(stored.toolName(), dto.getToolName());
    assertEquals(stored.toolCallId(), dto.getToolCallId());
    assertEquals(stored.toolVersion(), dto.getToolVersion());
    assertEquals(stored.targetType().name(), dto.getTargetType());
    assertEquals(stored.argumentsJson(), dto.getArgumentsJson());
    assertEquals(stored.status().name(), dto.getStatus());
    assertEquals(stored.permissionAction().name(), dto.getPermissionAction());
    assertNotNull(dto.getDeadlineAt());
    assertNotNull(dto.getCreateTime());

    ToolInvocationDTO single = service.getToolInvocation(Long.toString(stored.id()));
    assertEquals(stored.toolName(), single.getToolName());
  }

  /** Subagent task projection must read mapper output and parse the persisted report JSON. */
  @Test
  void projectsSubagentTasksForParentSession() {
    SessionFixture root = seedRoot("subagent", false);
    long parentInvocation = runIds.newRunEventId();
    long childSession = sessionIds.newSessionId();
    long childRun = runIds.newRunId();
    String reportJson =
        "{"
            + "\"childSessionId\":\""
            + childSession
            + "\","
            + "\"childRunId\":\""
            + childRun
            + "\","
            + "\"status\":\"SUCCEEDED\","
            + "\"finalReport\":\"done\","
            + "\"turnCount\":3,"
            + "\"toolCount\":2,"
            + "\"workingCopyPolicy\":\"FORK\","
            + "\"workingCopyRevision\":\"rev-1\","
            + "\"artifacts\":["
            + "  {\"artifactId\":\"11\",\"mediaType\":\"text/plain\",\"sizeBytes\":4}"
            + "]}";
    jdbc.update(
        "insert into harness_subagent_task"
            + " (parent_invocation_id, parent_session_id, child_session_id, child_run_id,"
            + " target_agent, working_copy_policy, working_copy_revision, max_turns,"
            + " idle_timeout_millis, status, report_json, gmt_create, gmt_modified) values"
            + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        parentInvocation,
        root.sessionId,
        childSession,
        childRun,
        "Child",
        "FORK",
        "rev-1",
        5,
        30000L,
        "SUCCEEDED",
        reportJson,
        Timestamp.from(NOW),
        Timestamp.from(NOW.plusSeconds(1)));

    List<SubagentTaskDTO> tasks = service.listSessionTasks(Long.toString(root.sessionId));
    assertEquals(1, tasks.size());
    SubagentTaskDTO task = tasks.get(0);
    assertEquals(Long.toString(parentInvocation), task.getParentInvocationId());
    assertEquals(Long.toString(root.sessionId), task.getParentSessionId());
    assertEquals(Long.toString(childSession), task.getChildSessionId());
    assertEquals(Long.toString(childRun), task.getChildRunId());
    assertEquals("Child", task.getTargetAgent());
    assertEquals(5, task.getMaxTurns());
    assertEquals(30000L, task.getIdleTimeoutMillis());

    SubagentTaskReportDTO report = task.getReport();
    assertNotNull(report);
    assertEquals(Long.toString(childSession), report.getChildSessionId());
    assertEquals(Long.toString(childRun), report.getChildRunId());
    assertEquals("SUCCEEDED", report.getStatus());
    assertEquals("done", report.getFinalReport());
    assertEquals(3, report.getTurnCount());
    assertEquals(2, report.getToolCount());
    assertEquals("FORK", report.getWorkingCopyPolicy());
    assertEquals("rev-1", report.getWorkingCopyRevision());
    assertEquals(1, report.getArtifacts().size());
    ToolArtifactRefDTO artifact = report.getArtifacts().get(0);
    assertEquals("11", artifact.getArtifactId());
    assertEquals("text/plain", artifact.getMediaType());
    assertEquals(4L, artifact.getSizeBytes());
  }

  /** Non-empty malformed report_json must throw IllegalStateException, not silently return null. */
  @Test
  void malformedReportJsonThrowsIllegalStateException() {
    long sessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(sessionId, null, "corrupted-report", false, NOW));
    jdbc.update(
        "insert into harness_subagent_task"
            + " (parent_invocation_id, parent_session_id, child_session_id, child_run_id,"
            + " target_agent, working_copy_policy, working_copy_revision, max_turns,"
            + " idle_timeout_millis, status, report_json, gmt_create, gmt_modified) values"
            + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        runIds.newRunEventId(),
        sessionId,
        sessionIds.newSessionId(),
        runIds.newRunId(),
        "Child",
        "FORK",
        "rev-1",
        5,
        30000L,
        "PENDING",
        "{not-json}",
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    assertThrows(
        IllegalStateException.class, () -> service.listSessionTasks(Long.toString(sessionId)));
  }

  /** Artifact resolution returns raw bytes; illegal ids throw IllegalArgumentException (400). */
  @Test
  void resolvesArtifactBytesAndDistinguishesMissingFromInvalid() {
    var ref = artifactStore.save("application/json", "raw", "{\"ok\":true}".getBytes());
    Artifact ok = service.getArtifact(ref.artifactId());
    assertArrayEquals("{\"ok\":true}".getBytes(), ok.content());
    assertEquals("application/json", ok.mediaType());
    assertEquals(ref.artifactId(), Long.toString(ok.id()));

    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> service.getArtifact("9999999999"));
    assertTrue(missing.getMessage().startsWith("unknown artifact:"));

    assertThrows(IllegalArgumentException.class, () -> service.getArtifact(""));
    assertThrows(IllegalArgumentException.class, () -> service.getArtifact("abc"));
    assertThrows(IllegalArgumentException.class, () -> service.getArtifact("0"));
    assertThrows(IllegalArgumentException.class, () -> service.getArtifact("-1"));
  }

  /** Unknown session/run must surface as IllegalArgumentException with the documented prefix. */
  @Test
  void unknownSessionAndRunYieldMissingResourceExceptions() {
    SessionFixture fixture = seedRoot("unknown", false);

    IllegalArgumentException sessionMissing =
        assertThrows(
            IllegalArgumentException.class, () -> service.listRootActivities("9999999999", 0, 10));
    assertTrue(sessionMissing.getMessage().startsWith("unknown session:"));

    IllegalArgumentException runMissing =
        assertThrows(
            IllegalArgumentException.class, () -> service.listRunEvents("9999999999", 0, 10));
    assertTrue(runMissing.getMessage().startsWith("unknown run:"));

    IllegalArgumentException sessionForTasks =
        assertThrows(IllegalArgumentException.class, () -> service.listSessionTasks("9999999999"));
    assertTrue(sessionForTasks.getMessage().startsWith("unknown session:"));

    // Existing session + valid cursor must not throw.
    assertTrue(service.listRootActivities(Long.toString(fixture.sessionId), 0, 10).isEmpty());

    // Sanity: tool invocation query against an unknown run yields an explicit missing prefix.
    IllegalArgumentException invocationRunMissing =
        assertThrows(
            IllegalArgumentException.class, () -> service.listToolInvocations("9999999999"));
    assertTrue(invocationRunMissing.getMessage().startsWith("unknown run:"));

    IllegalArgumentException invocationMissing =
        assertThrows(IllegalArgumentException.class, () -> service.getToolInvocation("9999999999"));
    assertTrue(invocationMissing.getMessage().startsWith("unknown tool invocation:"));
  }

  // -------------------- fixtures --------------------

  private SessionFixture seedRoot(String title, boolean yoloEnabled) {
    long sessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(sessionId, null, title, yoloEnabled, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    return new SessionFixture(sessionId, snapshotId);
  }

  private SessionFixture seedChild(long rootSessionId, String title) {
    long sessionId = sessionIds.newSessionId();
    Session child =
        new Session(
            sessionId,
            null,
            title,
            null,
            null,
            rootSessionId,
            rootSessionId,
            null,
            1,
            false,
            0,
            NOW,
            NOW);
    sessionStore.createFork(child, List.of());
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    return new SessionFixture(sessionId, snapshotId);
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static MessageEntryPayload assistantMessage(List<ToolCall> calls) {
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

  private static ToolBinding cloudBinding(String name) {
    return ToolBinding.of(
        new ToolDescriptor(
            name,
            "1",
            name,
            null,
            new ToolParamsSchema(
                "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.IDEMPOTENT,
            Duration.ofSeconds(30)));
  }

  private record SessionFixture(long sessionId, long snapshotId) {}
}
