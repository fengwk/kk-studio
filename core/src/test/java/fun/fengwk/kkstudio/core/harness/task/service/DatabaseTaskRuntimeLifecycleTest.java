package fun.fengwk.kkstudio.core.harness.task.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Durable task lifecycle edges: pending retry vs cancel-tree side effects. */
@SpringBootTest
class DatabaseTaskRuntimeLifecycleTest {

  @MockitoBean private ThreadKick threadKick;

  @Autowired private DatabaseTaskRuntime taskRuntime;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread_input");
    jdbc.update("delete from harness_thread");
    jdbc.update("delete from harness_session_entry");
    reset(threadKick);
  }

  @Test
  void failedLifecycleWithPendingRetryDoesNotCompleteTask() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_001L;
    long childThreadId = 6_600_101L;
    long childSessionId = 6_600_201L;

    HarnessThreadDO child = new HarnessThreadDO();
    child.setId(childThreadId);
    child.setSessionId(childSessionId);
    child.setHeadEntryId(1L);
    child.setStatus(ThreadStatus.WAITING.name());
    child.setInputSequence(1L);
    child.setVersion(0L);
    child.setCreateTime(now);
    child.setUpdateTime(now);
    threadMapper.insert(child);

    HarnessThreadEventDO failed = new HarnessThreadEventDO();
    failed.setId(6_600_301L);
    failed.setThreadId(childThreadId);
    failed.setEventType("thread_failed");
    failed.setPayloadJson("{\"schemaVersion\":1,\"message\":\"old failure\"}");
    failed.setCreateTime(now.minusSeconds(30));
    eventMapper.insert(failed);

    HarnessThreadInputDO pending = new HarnessThreadInputDO();
    pending.setId(6_600_401L);
    pending.setThreadId(childThreadId);
    pending.setSequence(1L);
    pending.setInputType("user_message");
    pending.setPayloadJson("{\"type\":\"message\"}");
    pending.setClientMessageId("pending-retry");
    pending.setStatus("queued");
    pending.setCreateTime(now);
    inputMapper.insert(pending);

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());
    assertEquals(TaskState.RUNNING, inspection.task().state());
    assertNull(taskMapper.find(parentInvocationId).getReportJson());
  }

  /** RETRYING 是未结束的 response debt，不能由旧 THREAD_FAILED 事件终态化子代理。 */
  @Test
  void failedLifecycleWithScheduledRetryDoesNotCompleteTask() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_002L;
    long childThreadId = 6_600_102L;
    long childSessionId = 6_600_202L;

    HarnessThreadDO child = new HarnessThreadDO();
    child.setId(childThreadId);
    child.setSessionId(childSessionId);
    child.setHeadEntryId(1L);
    child.setStatus(ThreadStatus.RETRYING.name());
    child.setInputSequence(0L);
    child.setRetryAttempt(1);
    child.setRetryAt(now.plusSeconds(30));
    child.setVersion(0L);
    child.setCreateTime(now);
    child.setUpdateTime(now);
    threadMapper.insert(child);

    HarnessThreadEventDO failed = new HarnessThreadEventDO();
    failed.setId(6_600_302L);
    failed.setThreadId(childThreadId);
    failed.setEventType(ThreadEventType.THREAD_FAILED.value());
    failed.setPayloadJson("{\"schemaVersion\":1,\"message\":\"old failure\"}");
    failed.setCreateTime(now.minusSeconds(30));
    eventMapper.insert(failed);

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());

    assertEquals(TaskState.RUNNING, inspection.task().state());
    assertNull(taskMapper.find(parentInvocationId).getReportJson());
  }

  /**
   * cancelTree marks the task CANCELLED and requests cancel on nonterminal child tools before
   * kicking; later model turns are blocked by turn admission, without interrupting an in-flight
   * provider call.
   */
  @Test
  void cancelTreeRequestsCancelOnChildTools() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_011L;
    long childThreadId = 6_600_111L;
    long childSessionId = 6_600_211L;
    long childToolId = 6_600_311L;

    HarnessThreadDO child = new HarnessThreadDO();
    child.setId(childThreadId);
    child.setSessionId(childSessionId);
    child.setHeadEntryId(1L);
    child.setStatus(ThreadStatus.WAITING.name());
    child.setInputSequence(0L);
    child.setVersion(0L);
    child.setCreateTime(now);
    child.setUpdateTime(now);
    threadMapper.insert(child);

    ToolInvocationDO open = new ToolInvocationDO();
    open.setId(childToolId);
    open.setThreadId(childThreadId);
    open.setAssistantEntryId(1L);
    open.setOrdinal(0);
    open.setToolCallId("call-1");
    open.setToolName("bash");
    open.setToolVersion("1");
    open.setTargetType(ToolTargetType.CLOUD.name());
    open.setArgumentsJson("{}");
    open.setStatus(ToolInvocationStatus.QUEUED.name());
    open.setPermissionAction("ALLOW");
    open.setSideEffect("WRITE");
    open.setDeadlineAt(now.plusHours(1));
    open.setCreateTime(now);
    open.setUpdateTime(now);
    invocationMapper.insert(open);

    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(2L);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    taskRuntime.cancelTree(parentInvocationId, Instant.now());
    taskRuntime.cancelTree(parentInvocationId, Instant.now());

    assertEquals(TaskState.CANCELLED.name(), taskMapper.find(parentInvocationId).getStatus());
    assertEquals(
        ToolInvocationStatus.CANCEL_REQUESTED.name(),
        invocationMapper.find(childToolId).getStatus());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(childThreadId).getStatus());
    verify(threadKick).kick(2L);
    verify(threadKick).kick(childThreadId);
    verifyNoMoreInteractions(threadKick);
    assertEquals(1, eventMapper.listAfter(2L, 0L, 10).size());
    assertEquals(
        TaskState.CANCELLED, taskRuntime.inspect(parentInvocationId, Instant.now()).task().state());
  }

  /**
   * Child terminal report atomically completes the task, wakes its waiting parent, then kicks it.
   */
  @Test
  void childFailureReportsAndResumesParentAfterCommit() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_021L;
    long parentThreadId = 6_600_121L;
    long childThreadId = 6_600_221L;
    insertThread(parentThreadId, parentThreadId + 10, now);
    insertThread(childThreadId, childThreadId + 10, now);
    threadMapper.updateStatusDirect(childThreadId, ThreadStatus.FAILED.name(), now);

    HarnessThreadEventDO failed = new HarnessThreadEventDO();
    failed.setId(6_600_321L);
    failed.setThreadId(childThreadId);
    failed.setEventType("thread_failed");
    failed.setPayloadJson("{\"schemaVersion\":1,\"message\":\"child failed\"}");
    failed.setCreateTime(now);
    eventMapper.insert(failed);
    insertRunningTask(parentInvocationId, parentThreadId, childThreadId, childThreadId + 10, now);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());

    assertEquals(TaskState.FAILED, inspection.task().state());
    assertNotNull(inspection.report());
    assertEquals("child failed", inspection.report().finalAssistantReport());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(parentThreadId).getStatus());
    verify(threadKick).kick(parentThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** 空闲 child 的最终 assistant head 形成成功报告，并唤醒等待 parent。 */
  @Test
  void idleChildWithFinalAssistantHeadCompletesSuccessfully() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_031L;
    long parentThreadId = 6_600_131L;
    long childThreadId = 6_600_231L;
    long childSessionId = 6_600_331L;
    long childHeadEntryId = 6_600_431L;
    insertThread(parentThreadId, parentThreadId + 10, now);
    insertThread(childThreadId, childSessionId, now);
    threadMapper.updateStatusDirect(childThreadId, ThreadStatus.IDLE.name(), now);
    insertAssistantHead(childSessionId, childHeadEntryId, "completed answer", now);
    updateThreadHead(childThreadId, childHeadEntryId);
    insertEvent(6_600_531L, childThreadId, ThreadEventType.THREAD_IDLE.value(), "{}", now);
    insertTerminalTool(6_600_631L, childThreadId, now);
    insertRunningTask(parentInvocationId, parentThreadId, childThreadId, childSessionId, now);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());

    assertEquals(TaskState.SUCCEEDED, inspection.task().state());
    assertNotNull(inspection.report());
    assertEquals("completed answer", inspection.report().finalAssistantReport());
    assertEquals(1, inspection.report().toolCount());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(parentThreadId).getStatus());
    assertEquals(TaskState.SUCCEEDED.name(), taskMapper.find(parentInvocationId).getStatus());
    assertTrue(
        eventMapper.listAfter(parentThreadId, 0L, 10).stream()
            .anyMatch(
                event -> ThreadEventType.SUBAGENT_COMPLETED.value().equals(event.getEventType())));
    verify(threadKick).kick(parentThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** idle 但没有 assistant 最终 head 不能伪装为成功，必须给父任务可解释失败报告。 */
  @Test
  void idleChildWithoutFinalAssistantHeadCompletesAsFailure() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long parentInvocationId = 6_600_041L;
    long parentThreadId = 6_600_141L;
    long childThreadId = 6_600_241L;
    long childSessionId = 6_600_341L;
    long childHeadEntryId = 6_600_441L;
    insertThread(parentThreadId, parentThreadId + 10, now);
    insertThread(childThreadId, childSessionId, now);
    threadMapper.updateStatusDirect(childThreadId, ThreadStatus.IDLE.name(), now);
    insertNonMessageHead(childSessionId, childHeadEntryId, now);
    updateThreadHead(childThreadId, childHeadEntryId);
    insertEvent(6_600_541L, childThreadId, ThreadEventType.THREAD_IDLE.value(), "{}", now);
    insertRunningTask(parentInvocationId, parentThreadId, childThreadId, childSessionId, now);

    TaskInspection inspection = taskRuntime.inspect(parentInvocationId, Instant.now());

    assertEquals(TaskState.FAILED, inspection.task().state());
    assertEquals(
        "child thread completed without final assistant head",
        inspection.report().finalAssistantReport());
    verify(threadKick).kick(parentThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** active lease、未完成 Tool、排队输入及缺少 lifecycle 事件时都保持 RUNNING。 */
  @Test
  void inspectKeepsTaskRunningWhileChildIsActiveOrCompletionIsNotProven() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long base = 6_601_000L;

    insertThread(base + 1, base + 101, now);
    HarnessThreadDO leased = threadMapper.find(base + 1);
    leased.setProcessorToken("active");
    leased.setProcessorUntil(now.plusMinutes(1));
    jdbc.update(
        "update harness_thread set processor_token = ?, processor_until = ? where id = ?",
        leased.getProcessorToken(),
        leased.getProcessorUntil(),
        leased.getId());
    insertRunningTask(base + 201, base + 301, base + 1, base + 101, now);

    insertThread(base + 2, base + 102, now);
    insertNonterminalTool(base + 202, base + 2, now);
    insertRunningTask(base + 202, base + 302, base + 2, base + 102, now);

    insertThread(base + 3, base + 103, now);
    insertQueuedInput(base + 203, base + 3, now);
    insertRunningTask(base + 203, base + 303, base + 3, base + 103, now);

    insertThread(base + 4, base + 104, now);
    insertRunningTask(base + 204, base + 304, base + 4, base + 104, now);

    assertEquals(TaskState.RUNNING, taskRuntime.inspect(base + 201, Instant.now()).task().state());
    assertEquals(TaskState.RUNNING, taskRuntime.inspect(base + 202, Instant.now()).task().state());
    assertEquals(TaskState.RUNNING, taskRuntime.inspect(base + 203, Instant.now()).task().state());
    assertEquals(TaskState.RUNNING, taskRuntime.inspect(base + 204, Instant.now()).task().state());
    assertNull(taskMapper.find(base + 201).getReportJson());
    assertNull(taskMapper.find(base + 202).getReportJson());
    assertNull(taskMapper.find(base + 203).getReportJson());
    assertNull(taskMapper.find(base + 204).getReportJson());
    verifyNoMoreInteractions(threadKick);
  }

  @Test
  void inspectRejectsMissingTaskAndMissingChildThread() {
    assertThrows(
        IllegalArgumentException.class, () -> taskRuntime.inspect(6_602_001L, Instant.now()));

    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertRunningTask(6_602_002L, 6_602_101L, 6_602_201L, 6_602_301L, now);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> taskRuntime.inspect(6_602_002L, Instant.now()));
    assertTrue(error.getMessage().contains("child thread missing"));
    verifyNoMoreInteractions(threadKick);
  }

  /** thread_failed 的 reason 被透传；损坏 payload 则稳定回退为通用失败说明。 */
  @Test
  void failedLifecycleUsesReasonAndMalformedPayloadFallback() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long reasonParentThread = 6_603_101L;
    long reasonChildThread = 6_603_201L;
    insertThread(reasonParentThread, reasonParentThread + 10, now);
    insertThread(reasonChildThread, reasonChildThread + 10, now);
    threadMapper.updateStatusDirect(reasonChildThread, ThreadStatus.FAILED.name(), now);
    insertEvent(
        6_603_301L,
        reasonChildThread,
        ThreadEventType.THREAD_FAILED.value(),
        "{\"reason\":\"tool exhausted\"}",
        now);
    insertRunningTask(
        6_603_001L, reasonParentThread, reasonChildThread, reasonChildThread + 10, now);

    TaskInspection reasonInspection = taskRuntime.inspect(6_603_001L, Instant.now());
    assertEquals(TaskState.FAILED, reasonInspection.task().state());
    assertEquals("tool exhausted", reasonInspection.report().finalAssistantReport());

    long malformedParentThread = 6_603_102L;
    long malformedChildThread = 6_603_202L;
    insertThread(malformedParentThread, malformedParentThread + 10, now);
    insertThread(malformedChildThread, malformedChildThread + 10, now);
    threadMapper.updateStatusDirect(malformedChildThread, ThreadStatus.FAILED.name(), now);
    insertEvent(
        6_603_302L, malformedChildThread, ThreadEventType.THREAD_FAILED.value(), "not json", now);
    insertRunningTask(
        6_603_002L, malformedParentThread, malformedChildThread, malformedChildThread + 10, now);

    TaskInspection malformedInspection = taskRuntime.inspect(6_603_002L, Instant.now());
    assertEquals(TaskState.FAILED, malformedInspection.task().state());
    assertEquals("child thread failed", malformedInspection.report().finalAssistantReport());
    verify(threadKick).kick(reasonParentThread);
    verify(threadKick).kick(malformedParentThread);
    verifyNoMoreInteractions(threadKick);
  }

  private void insertThread(long id, long sessionId, LocalDateTime now) {
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(id);
    thread.setSessionId(sessionId);
    thread.setActiveAgentDefinitionId(1L);
    thread.setActiveAgentName("parent");
    thread.setModelId("1");
    thread.setVariant("default");
    thread.setYoloEnabled(false);
    thread.setHeadEntryId(1L);
    thread.setStatus(ThreadStatus.WAITING.name());
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(now);
    thread.setUpdateTime(now);
    threadMapper.insert(thread);
  }

  private void updateThreadHead(long threadId, long headEntryId) {
    jdbc.update("update harness_thread set head_entry_id = ? where id = ?", headEntryId, threadId);
  }

  private void insertAssistantHead(
      long sessionId, long entryId, String text, LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(entryId);
    entry.setSessionId(sessionId);
    entry.setEntryType(SessionEntryType.MESSAGE.value());
    entry.setPayloadJson(
        new SessionEntryJsonCodec()
            .encode(
                new MessageEntryPayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
                    new AssistantMessageMetadata(
                        ProviderStopReason.COMPLETED,
                        new ModelUsage(0, 0, 0, 0, 0, 0, 0),
                        new ModelCost(
                            "USD",
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO)))));
    entry.setCreateTime(timestamp);
    entryMapper.insert(entry);
  }

  private void insertNonMessageHead(long sessionId, long entryId, LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(entryId);
    entry.setSessionId(sessionId);
    entry.setEntryType(SessionEntryType.ROOT.value());
    entry.setPayloadJson("{}");
    entry.setCreateTime(timestamp);
    entryMapper.insert(entry);
  }

  private void insertEvent(
      long id, long threadId, String type, String payloadJson, LocalDateTime timestamp) {
    HarnessThreadEventDO event = new HarnessThreadEventDO();
    event.setId(id);
    event.setThreadId(threadId);
    event.setEventType(type);
    event.setPayloadJson(payloadJson);
    event.setCreateTime(timestamp);
    eventMapper.insert(event);
  }

  private void insertTerminalTool(long id, long threadId, LocalDateTime timestamp) {
    ToolInvocationDO invocation = baseTool(id, threadId, timestamp);
    invocation.setStatus(ToolInvocationStatus.SUCCEEDED.name());
    invocation.setFinishedAt(timestamp);
    invocationMapper.insert(invocation);
  }

  private void insertNonterminalTool(long id, long threadId, LocalDateTime timestamp) {
    ToolInvocationDO invocation = baseTool(id, threadId, timestamp);
    invocation.setStatus(ToolInvocationStatus.RUNNING.name());
    invocation.setLeaseOwner("worker");
    invocation.setLeaseUntil(timestamp.plusMinutes(1));
    invocation.setStartedAt(timestamp);
    invocationMapper.insert(invocation);
  }

  private ToolInvocationDO baseTool(long id, long threadId, LocalDateTime timestamp) {
    ToolInvocationDO invocation = new ToolInvocationDO();
    invocation.setId(id);
    invocation.setThreadId(threadId);
    invocation.setAssistantEntryId(id);
    invocation.setOrdinal(0);
    invocation.setToolCallId("call-" + id);
    invocation.setToolName("tool");
    invocation.setToolVersion("1");
    invocation.setTargetType(ToolTargetType.CONTROL.name());
    invocation.setArgumentsJson("{}");
    invocation.setPermissionAction("ALLOW");
    invocation.setSideEffect("READ_ONLY");
    invocation.setDeadlineAt(timestamp.plusHours(1));
    invocation.setCreateTime(timestamp);
    invocation.setUpdateTime(timestamp);
    return invocation;
  }

  private void insertQueuedInput(long id, long threadId, LocalDateTime timestamp) {
    HarnessThreadInputDO input = new HarnessThreadInputDO();
    input.setId(id);
    input.setThreadId(threadId);
    input.setSequence(1L);
    input.setInputType("user_message");
    input.setPayloadJson("{\"type\":\"message\"}");
    input.setClientMessageId("queued-" + id);
    input.setStatus("queued");
    input.setCreateTime(timestamp);
    inputMapper.insert(input);
  }

  private void insertRunningTask(
      long parentInvocationId,
      long parentThreadId,
      long childThreadId,
      long childSessionId,
      LocalDateTime now) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentThreadId + 20);
    task.setParentThreadId(parentThreadId);
    task.setRootThreadId(parentThreadId);
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(4);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }
}
