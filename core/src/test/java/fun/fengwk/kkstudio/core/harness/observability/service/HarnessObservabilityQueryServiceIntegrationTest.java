package fun.fengwk.kkstudio.core.harness.observability.service;

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
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskReportDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;
import fun.fengwk.kkstudio.share.model.ToolArtifactRefDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Observability query integration: ThreadEvent id-cursor paging, root-tree activity projection,
 * tool invocation / subagent task DTO fields, and artifact lookup against the current Thread
 * schema.
 */
@SpringBootTest(classes = CoreTestApplication.class)
class HarnessObservabilityQueryServiceIntegrationTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 1, 0, 0);

  @Autowired private HarnessObservabilityQueryServiceImpl service;
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

  /** Thread events page by exclusive event-id cursor and project decimal-string Snowflake ids. */
  @Test
  void pagesThreadEventsByEventIdCursor() {
    long threadId = 7_100_001L;
    insertThread(threadId, 7_100_101L);
    // Deliberately reverse createTime so ordering must follow id ASC, not wall-clock.
    insertEvent(7_100_201L, threadId, ThreadEventType.TURN_STARTED, NOW.plusSeconds(5));
    insertEvent(7_100_202L, threadId, ThreadEventType.ASSISTANT_STARTED, NOW.plusSeconds(1));
    insertEvent(7_100_203L, threadId, ThreadEventType.ASSISTANT_COMPLETED, NOW.plusSeconds(3));
    insertEvent(7_100_204L, threadId, ThreadEventType.THREAD_IDLE, NOW.plusSeconds(2));
    insertEvent(7_100_205L, threadId, ThreadEventType.THREAD_STARTED, NOW);

    List<ThreadEventDTO> page1 = service.listThreadEvents(Long.toString(threadId), 0, 2);
    assertEquals(
        List.of("7100201", "7100202"), page1.stream().map(ThreadEventDTO::getEventId).toList());
    assertEquals(Long.toString(threadId), page1.get(0).getThreadId());
    assertEquals(ThreadEventType.TURN_STARTED.value(), page1.get(0).getEventType());
    assertNotNull(page1.get(0).getCreateTime());

    List<ThreadEventDTO> page2 =
        service.listThreadEvents(
            Long.toString(threadId), Long.parseLong(page1.get(1).getEventId()), 10);
    assertEquals(
        List.of("7100203", "7100204", "7100205"),
        page2.stream().map(ThreadEventDTO::getEventId).toList());

    // Unknown thread id is a no-op query; invalid ids still reject at the parser boundary.
    assertTrue(service.listThreadEvents("9999999999", 0, 10).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> service.listThreadEvents("abc", 0, 10));
    assertThrows(IllegalArgumentException.class, () -> service.listThreadEvents("0", 0, 10));
  }

  /**
   * Root activity resolves child sessions to the root tree, pages by exclusive eventId, and keeps
   * sibling root trees isolated.
   */
  @Test
  void pagesRootActivitiesAcrossChildSessionsWithRootIsolation() {
    long rootSessionId = 7_200_001L;
    long childSessionId = 7_200_002L;
    long otherRootSessionId = 7_200_003L;
    long rootThreadId = 7_200_101L;
    long childThreadId = 7_200_102L;
    long otherThreadId = 7_200_103L;

    insertRootSession(rootSessionId, "root");
    insertChildSession(childSessionId, rootSessionId, "child");
    insertRootSession(otherRootSessionId, "other");
    insertThread(rootThreadId, rootSessionId);
    insertThread(childThreadId, childSessionId);
    insertThread(otherThreadId, otherRootSessionId);

    insertEvent(7_200_201L, rootThreadId, ThreadEventType.TURN_STARTED, NOW);
    insertEvent(7_200_202L, childThreadId, ThreadEventType.SUBAGENT_STARTED, NOW.plusSeconds(1));
    insertEvent(7_200_203L, otherThreadId, ThreadEventType.TURN_STARTED, NOW.plusSeconds(2));

    // Query via the child session must still surface the whole root tree (root + child events).
    List<RootActivityDTO> activity =
        service.listRootActivities(Long.toString(childSessionId), 0, 10);
    assertEquals(2, activity.size());
    assertEquals(Long.toString(rootSessionId), activity.get(0).getRootSessionId());
    assertEquals(Long.toString(rootSessionId), activity.get(1).getRootSessionId());
    assertEquals(Long.toString(rootThreadId), activity.get(0).getThreadId());
    assertEquals(Long.toString(childThreadId), activity.get(1).getThreadId());
    assertEquals(
        List.of("7200201", "7200202"), activity.stream().map(RootActivityDTO::getEventId).toList());

    long cursor = Long.parseLong(activity.get(0).getEventId());
    List<RootActivityDTO> tail =
        service.listRootActivities(Long.toString(childSessionId), cursor, 10);
    assertEquals(1, tail.size());
    assertEquals("7200202", tail.get(0).getEventId());
    assertEquals(Long.toString(childSessionId), tail.get(0).getSessionId());

    assertTrue(
        service.listRootActivities(Long.toString(childSessionId), 0, 50).stream()
            .allMatch(e -> rootSessionId == Long.parseLong(e.getRootSessionId())));

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class, () -> service.listRootActivities("9999999999", 0, 10));
    assertTrue(missing.getMessage().startsWith("unknown session:"));
  }

  /** Tool invocation list is ordinal-ordered and projects the durable Thread-scoped fields. */
  @Test
  void projectsToolInvocationsByOrdinalWithPersistentFields() {
    long threadId = 7_300_001L;
    long assistantEntryId = 7_300_501L;
    insertThread(threadId, 7_300_101L);

    // Insert higher ordinal first; projection must still return ordinal ASC.
    insertInvocation(
        7_300_301L, threadId, assistantEntryId, 1, "call-b", "read", "QUEUED", "ALLOW", null);
    insertInvocation(
        7_300_302L, threadId, assistantEntryId, 0, "call-a", "write", "RUNNING", "ASK", "ALLOW");

    List<ToolInvocationDTO> projected = service.listToolInvocations(Long.toString(threadId));
    assertEquals(2, projected.size());
    assertEquals(List.of(0, 1), projected.stream().map(ToolInvocationDTO::getOrdinal).toList());

    ToolInvocationDTO first = projected.get(0);
    assertEquals("7300302", first.getId());
    assertEquals(Long.toString(threadId), first.getThreadId());
    assertEquals(Long.toString(assistantEntryId), first.getAssistantEntryId());
    assertEquals("write", first.getToolName());
    assertEquals("1", first.getToolVersion());
    assertEquals("CLOUD", first.getTargetType());
    assertEquals("{\"path\":\"notes.txt\"}", first.getArgumentsJson());
    assertEquals("RUNNING", first.getStatus());
    assertEquals("ASK", first.getPermissionAction());
    assertEquals("ALLOW", first.getPermissionDecision());
    assertNotNull(first.getDeadlineAt());
    assertNotNull(first.getCreateTime());

    ToolInvocationDTO single = service.getToolInvocation("7300302");
    assertEquals("write", single.getToolName());
    assertEquals(0, single.getOrdinal());

    assertTrue(service.listToolInvocations("9999999999").isEmpty());
    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> service.getToolInvocation("9999999999"));
    assertTrue(missing.getMessage().startsWith("unknown tool invocation:"));
    assertThrows(IllegalArgumentException.class, () -> service.getToolInvocation("abc"));
  }

  /** Session task projection surfaces parent/child Thread ids and decodes the report payload. */
  @Test
  void projectsSessionTasksAndRejectsMalformedReportJson() {
    long parentSessionId = 7_400_001L;
    long parentThreadId = 7_400_101L;
    long childSessionId = 7_400_002L;
    long childThreadId = 7_400_102L;
    long parentInvocationId = 7_400_301L;
    insertRootSession(parentSessionId, "parent-tasks");

    String reportJson =
        TaskResultFormatter.json(
            new TaskReport(
                childSessionId,
                childThreadId,
                TaskState.SUCCEEDED,
                "done",
                List.of(new ArtifactRef("11", "text/plain", 4L)),
                3,
                2,
                WorkingCopyPolicy.FORK,
                "rev-1"));

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentSessionId);
    task.setParentThreadId(parentThreadId);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("Child");
    task.setWorkingCopyPolicy(WorkingCopyPolicy.FORK.name());
    task.setWorkingCopyRevision("rev-1");
    task.setMaxTurns(5);
    task.setStatus(TaskState.SUCCEEDED.name());
    task.setReportJson(reportJson);
    task.setCreateTime(NOW);
    task.setUpdateTime(NOW.plusSeconds(1));
    taskMapper.insert(task);

    List<SubagentTaskDTO> tasks = service.listSessionTasks(Long.toString(parentSessionId));
    assertEquals(1, tasks.size());
    SubagentTaskDTO projected = tasks.get(0);
    assertEquals(Long.toString(parentInvocationId), projected.getParentInvocationId());
    assertEquals(Long.toString(parentSessionId), projected.getParentSessionId());
    assertEquals(Long.toString(parentThreadId), projected.getParentThreadId());
    assertEquals(Long.toString(childSessionId), projected.getChildSessionId());
    assertEquals(Long.toString(childThreadId), projected.getChildThreadId());
    assertEquals("Child", projected.getTargetAgent());
    assertEquals(5, projected.getMaxTurns());
    assertEquals(TaskState.SUCCEEDED.name(), projected.getStatus());

    SubagentTaskReportDTO report = projected.getReport();
    assertNotNull(report);
    assertEquals(Long.toString(childSessionId), report.getChildSessionId());
    assertEquals(Long.toString(childThreadId), report.getChildThreadId());
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

    // Malformed non-blank report_json must fail conversion rather than silently null the report.
    long badSessionId = 7_400_011L;
    insertRootSession(badSessionId, "bad-report");
    HarnessSubagentTaskDO bad = new HarnessSubagentTaskDO();
    bad.setParentInvocationId(7_400_311L);
    bad.setParentSessionId(badSessionId);
    bad.setParentThreadId(7_400_111L);
    bad.setChildSessionId(7_400_012L);
    bad.setChildThreadId(7_400_112L);
    bad.setTargetAgent("Child");
    bad.setWorkingCopyPolicy(WorkingCopyPolicy.FORK.name());
    bad.setWorkingCopyRevision("rev-1");
    bad.setMaxTurns(5);
    bad.setStatus(TaskState.RUNNING.name());
    bad.setReportJson("{not-json}");
    bad.setCreateTime(NOW);
    bad.setUpdateTime(NOW);
    taskMapper.insert(bad);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listSessionTasks(Long.toString(badSessionId)));

    // Unknown parent session simply returns empty under the current query contract.
    assertTrue(service.listSessionTasks("9999999999").isEmpty());
  }

  /** Artifact lookup returns raw bytes; missing/invalid ids map to IllegalArgumentException. */
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

  // -------------------- fixtures --------------------

  private void insertRootSession(long sessionId, String title) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(sessionId);
    session.setAgentDefinitionId(1L);
    session.setTitle(title);
    session.setRootSessionId(sessionId);
    session.setDepth(0);
    session.setVersion(0L);
    session.setCreateTime(NOW);
    session.setUpdateTime(NOW);
    sessionMapper.insert(session);
  }

  private void insertChildSession(long sessionId, long rootSessionId, String title) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(sessionId);
    session.setAgentDefinitionId(1L);
    session.setTitle(title);
    session.setParentSessionId(rootSessionId);
    session.setRootSessionId(rootSessionId);
    session.setDepth(1);
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
    thread.setAgentDefinitionId(1L);
    thread.setRuntimeConfigJson("{}");
    thread.setYoloEnabled(false);
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(NOW);
    thread.setUpdateTime(NOW);
    threadMapper.insert(thread);
  }

  private void insertEvent(
      long eventId, long threadId, ThreadEventType type, LocalDateTime createTime) {
    HarnessThreadEventDO event = new HarnessThreadEventDO();
    event.setId(eventId);
    event.setThreadId(threadId);
    event.setEventType(type.value());
    event.setPayloadJson("{\"schemaVersion\":1}");
    event.setCreateTime(createTime);
    eventMapper.insert(event);
  }

  private void insertInvocation(
      long id,
      long threadId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String status,
      String permissionAction,
      String permissionDecision) {
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
    row.setPermissionAction(permissionAction);
    row.setPermissionDecision(permissionDecision);
    row.setSideEffect("IDEMPOTENT");
    row.setDeadlineAt(NOW.plusHours(1));
    row.setCreateTime(NOW);
    row.setUpdateTime(NOW);
    if ("RUNNING".equals(status)) {
      row.setStartedAt(NOW.plusSeconds(1));
    }
    invocationMapper.insert(row);
  }
}
