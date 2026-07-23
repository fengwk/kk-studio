package fun.fengwk.kkstudio.core.harness.tool.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDecisionDTO;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tool permission decision integration: Thread→Invocation lock path, durable events, terminal
 * states, and after-commit ThreadKick. Processor is isolated via {@link MockitoBean}.
 */
@SpringBootTest
class ToolInvocationDecisionServiceTest {

  private static final long THREAD_ID = 6_100_001L;
  private static final long INVOCATION_ID = 6_200_001L;
  private static final long ASSISTANT_ENTRY_ID = 6_300_001L;
  private static final String TOOL_CALL_ID = "call-" + INVOCATION_ID;

  @MockitoBean private ThreadKick threadKick;

  @Autowired private ToolInvocationDecisionService decisionService;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread");
    reset(threadKick);
  }

  /** ALLOW: WAITING_APPROVAL → QUEUED，写 permission_resolved，提交后 kick 一次。 */
  @Test
  void allowTransitionsToQueuedWritesPermissionResolvedAndKicksAfterCommit() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(THREAD_ID, now);
    insertWaitingApproval(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);

    ToolInvocationDTO dto = decisionService.decide(INVOCATION_ID, decision("allow"));

    assertEquals(Long.toString(INVOCATION_ID), dto.getId());
    assertEquals(Long.toString(THREAD_ID), dto.getThreadId());
    assertEquals(ToolInvocationStatus.QUEUED.name(), dto.getStatus());
    assertEquals("ALLOW", dto.getPermissionDecision());

    ToolInvocationDO row = invocationMapper.find(INVOCATION_ID);
    assertEquals(ToolInvocationStatus.QUEUED.name(), row.getStatus());
    assertEquals("ALLOW", row.getPermissionDecision());
    assertNull(row.getResultJson());
    assertNull(row.getErrorMessage());
    assertNull(row.getFinishedAt());

    List<HarnessThreadEventDO> events = eventMapper.listAfter(THREAD_ID, 0L, 20);
    assertEquals(1, events.size());
    HarnessThreadEventDO resolved = events.get(0);
    assertEquals(ThreadEventType.PERMISSION_RESOLVED.value(), resolved.getEventType());
    assertTrue(resolved.getPayloadJson().contains("\"invocationId\":\"" + INVOCATION_ID + "\""));
    assertTrue(resolved.getPayloadJson().contains("\"status\":\"QUEUED\""));
    assertTrue(resolved.getPayloadJson().contains("\"decision\":\"ALLOW\""));

    verify(threadKick, times(1)).kick(THREAD_ID);
    verifyNoMoreInteractions(threadKick);
  }

  /**
   * DENY: WAITING_APPROVAL → FAILED，持久化 canonical ToolResult/error/finishedAt；写 permission_resolved
   * 后 tool_completed（按 event id 序），提交后 kick。
   */
  @Test
  void denyFailsWithCanonicalResultWritesOrderedEventsAndKicksAfterCommit() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(THREAD_ID, now);
    insertWaitingApproval(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);

    ToolInvocationDTO dto = decisionService.decide(INVOCATION_ID, decision("deny"));

    assertEquals(ToolInvocationStatus.FAILED.name(), dto.getStatus());
    assertEquals("DENY", dto.getPermissionDecision());

    ToolInvocationDO row = invocationMapper.find(INVOCATION_ID);
    assertEquals(ToolInvocationStatus.FAILED.name(), row.getStatus());
    assertEquals("DENY", row.getPermissionDecision());
    assertEquals("Permission denied by user.", row.getErrorMessage());
    assertNotNull(row.getFinishedAt());
    assertEquals(
        ToolResultJsonCodec.encode(ToolResult.error(TOOL_CALL_ID, "Permission denied by user.")),
        row.getResultJson());

    List<HarnessThreadEventDO> events = eventMapper.listAfter(THREAD_ID, 0L, 20);
    assertEquals(2, events.size());
    assertTrue(events.get(0).getId() < events.get(1).getId());
    assertEquals(ThreadEventType.PERMISSION_RESOLVED.value(), events.get(0).getEventType());
    assertEquals(ThreadEventType.TOOL_COMPLETED.value(), events.get(1).getEventType());
    assertTrue(events.get(0).getPayloadJson().contains("\"status\":\"FAILED\""));
    assertTrue(events.get(0).getPayloadJson().contains("\"decision\":\"DENY\""));
    assertTrue(events.get(1).getPayloadJson().contains("\"toolCallId\":\"" + TOOL_CALL_ID + "\""));
    assertTrue(events.get(1).getPayloadJson().contains("\"error\":true"));
    assertTrue(
        events.get(1).getPayloadJson().contains("\"errorMessage\":\"Permission denied by user.\""));

    verify(threadKick, times(1)).kick(THREAD_ID);
    verifyNoMoreInteractions(threadKick);
  }

  /** missing invocation：IllegalArgumentException，不写事件、不 kick。 */
  @Test
  void missingInvocationFailsWithoutEventsOrKick() {
    assertThrows(
        IllegalArgumentException.class,
        () -> decisionService.decide(INVOCATION_ID, decision("allow")));

    assertEquals(0, eventMapper.listAfter(THREAD_ID, 0L, 20).size());
    verifyNoInteractions(threadKick);
  }

  /** owning thread 缺失：IllegalStateException，不写事件、不 kick。 */
  @Test
  void missingOwningThreadFailsWithoutEventsOrKick() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertWaitingApproval(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> decisionService.decide(INVOCATION_ID, decision("allow")));
    assertTrue(error.getMessage().contains("owning thread missing"));

    ToolInvocationDO row = invocationMapper.find(INVOCATION_ID);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL.name(), row.getStatus());
    assertNull(row.getPermissionDecision());
    assertEquals(0, eventMapper.listAfter(THREAD_ID, 0L, 20).size());
    verifyNoInteractions(threadKick);
  }

  /** 非 WAITING_APPROVAL：IllegalStateException，状态不变、不写事件、不 kick。 */
  @Test
  void nonWaitingApprovalFailsWithoutEventsOrKick() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(THREAD_ID, now);
    ToolInvocationDO queued = baseInvocation(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);
    queued.setStatus(ToolInvocationStatus.QUEUED.name());
    queued.setPermissionAction("ALLOW");
    invocationMapper.insert(queued);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> decisionService.decide(INVOCATION_ID, decision("allow")));
    assertTrue(error.getMessage().contains("not waiting approval"));

    assertEquals(
        ToolInvocationStatus.QUEUED.name(), invocationMapper.find(INVOCATION_ID).getStatus());
    assertEquals(0, eventMapper.listAfter(THREAD_ID, 0L, 20).size());
    verifyNoInteractions(threadKick);
  }

  /** 非法 decision：IllegalArgumentException，不写事件、不 kick。 */
  @Test
  void illegalDecisionFailsWithoutEventsOrKick() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(THREAD_ID, now);
    insertWaitingApproval(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);

    assertThrows(
        IllegalArgumentException.class,
        () -> decisionService.decide(INVOCATION_ID, decision("maybe")));

    ToolInvocationDO row = invocationMapper.find(INVOCATION_ID);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL.name(), row.getStatus());
    assertNull(row.getPermissionDecision());
    assertEquals(0, eventMapper.listAfter(THREAD_ID, 0L, 20).size());
    verifyNoInteractions(threadKick);
  }

  /** Child Thread 的根 UI 决策仍更新源 Invocation，并将 resolved 事件镜像到 delegation root。 */
  @Test
  void childDecisionRelaysResolvedEventToRootAndResumesChildThread() {
    long rootThreadId = THREAD_ID + 10;
    long childThreadId = THREAD_ID + 11;
    long childInvocationId = INVOCATION_ID + 11;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(rootThreadId, now);
    insertThread(childThreadId, now);
    insertWaitingApproval(childInvocationId, childThreadId, ASSISTANT_ENTRY_ID, now);
    insertChildTask(childThreadId, rootThreadId, now);

    decisionService.decide(childInvocationId, decision("allow"));

    List<HarnessThreadEventDO> childEvents = eventMapper.listAfter(childThreadId, 0L, 10);
    List<HarnessThreadEventDO> rootEvents = eventMapper.listAfter(rootThreadId, 0L, 10);
    assertEquals(1, childEvents.size());
    assertEquals(1, rootEvents.size());
    assertEquals(ThreadEventType.PERMISSION_RESOLVED.value(), childEvents.get(0).getEventType());
    assertEquals(ThreadEventType.PERMISSION_RESOLVED.value(), rootEvents.get(0).getEventType());
    assertEquals(ASSISTANT_ENTRY_ID, childEvents.get(0).getSubjectEntryId());
    assertNull(rootEvents.get(0).getSubjectEntryId());
    assertEquals(childEvents.get(0).getPayloadJson(), rootEvents.get(0).getPayloadJson());
    assertTrue(
        rootEvents.get(0).getPayloadJson().contains("\"invocationId\":\"" + childInvocationId));
    assertEquals(
        ToolInvocationStatus.QUEUED.name(), invocationMapper.find(childInvocationId).getStatus());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(childThreadId).getStatus());
    verify(threadKick).kick(childThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** 并发的 allow/deny 通过 Thread→Invocation 锁和 CAS 线性化，只有首个持久决定生效。 */
  @Test
  void concurrentDecisionsAreFirstWriterWins() throws Exception {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(THREAD_ID, now);
    insertWaitingApproval(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Object> allow =
          pool.submit(
              () -> {
                start.await();
                return decideOrCapture(INVOCATION_ID, "allow");
              });
      Future<Object> deny =
          pool.submit(
              () -> {
                start.await();
                return decideOrCapture(INVOCATION_ID, "deny");
              });
      start.countDown();

      List<Object> outcomes = List.of(allow.get(), deny.get());
      assertEquals(1, outcomes.stream().filter(ToolInvocationDTO.class::isInstance).count());
      assertEquals(1, outcomes.stream().filter(IllegalStateException.class::isInstance).count());
      ToolInvocationDO invocation = invocationMapper.find(INVOCATION_ID);
      assertTrue(List.of("ALLOW", "DENY").contains(invocation.getPermissionDecision()));
      assertEquals(
          "ALLOW".equals(invocation.getPermissionDecision())
              ? ToolInvocationStatus.QUEUED.name()
              : ToolInvocationStatus.FAILED.name(),
          invocation.getStatus());
      verify(threadKick).kick(THREAD_ID);
      verifyNoMoreInteractions(threadKick);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 直接调用（没有 Spring transaction synchronization）仍同步 kick，避免嵌入式调用丢失恢复信号。 */
  @Test
  void directServiceInvocationKicksSynchronouslyWithoutTransactionSynchronization() {
    ToolInvocationMapper directInvocationMapper = mock(ToolInvocationMapper.class);
    HarnessThreadMapper directThreadMapper = mock(HarnessThreadMapper.class);
    HarnessSubagentTaskMapper directTaskMapper = mock(HarnessSubagentTaskMapper.class);
    ThreadEventStore directEventStore = mock(ThreadEventStore.class);
    ThreadKick directThreadKick = mock(ThreadKick.class);
    ToolInvocationDecisionService directService =
        new ToolInvocationDecisionService(
            directInvocationMapper,
            directThreadMapper,
            directTaskMapper,
            directEventStore,
            directThreadKick);
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    ToolInvocationDO invocation = baseInvocation(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, now);
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(THREAD_ID);
    when(directInvocationMapper.find(INVOCATION_ID)).thenReturn(invocation);
    when(directThreadMapper.findForUpdate(THREAD_ID)).thenReturn(thread);
    when(directInvocationMapper.findForUpdate(INVOCATION_ID)).thenReturn(invocation);
    when(directInvocationMapper.resolvePermission(
            eq(INVOCATION_ID),
            anyString(),
            anyString(),
            anyString(),
            any(),
            isNull(),
            isNull(),
            isNull(),
            any()))
        .thenAnswer(
            ignored -> {
              invocation.setStatus(ToolInvocationStatus.QUEUED.name());
              invocation.setPermissionDecision("ALLOW");
              return 1;
            });

    ToolInvocationDTO dto = directService.decide(INVOCATION_ID, decision("allow"));

    assertEquals(ToolInvocationStatus.QUEUED.name(), dto.getStatus());
    verify(directThreadKick).kick(THREAD_ID);
    verifyNoMoreInteractions(directThreadKick);
  }

  private static ToolInvocationDecisionDTO decision(String value) {
    ToolInvocationDecisionDTO dto = new ToolInvocationDecisionDTO();
    dto.setDecision(value);
    return dto;
  }

  private void insertThread(long id, LocalDateTime now) {
    HarnessThreadDO row = new HarnessThreadDO();
    row.setId(id);
    row.setSessionId(id + 10);
    row.setHeadEntryId(id + 20);
    row.setStatus(ThreadStatus.WAITING.name());
    row.setInputSequence(0L);
    row.setVersion(0L);
    row.setCreateTime(now);
    row.setUpdateTime(now);
    threadMapper.insert(row);
  }

  private void insertChildTask(long childThreadId, long rootThreadId, LocalDateTime now) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(childThreadId + 1000);
    task.setParentSessionId(childThreadId + 1001);
    task.setParentThreadId(rootThreadId);
    task.setRootThreadId(rootThreadId);
    task.setChildSessionId(childThreadId + 1002);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("child");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(5);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }

  private Object decideOrCapture(long invocationId, String value) {
    try {
      return decisionService.decide(invocationId, decision(value));
    } catch (RuntimeException error) {
      return error;
    }
  }

  private void insertWaitingApproval(
      long id, long threadId, long assistantEntryId, LocalDateTime now) {
    ToolInvocationDO inv = baseInvocation(id, threadId, assistantEntryId, now);
    inv.setStatus(ToolInvocationStatus.WAITING_APPROVAL.name());
    inv.setPermissionAction("ASK");
    inv.setSideEffect("WRITE");
    invocationMapper.insert(inv);
  }

  private static ToolInvocationDO baseInvocation(
      long id, long threadId, long assistantEntryId, LocalDateTime now) {
    ToolInvocationDO inv = new ToolInvocationDO();
    inv.setId(id);
    inv.setThreadId(threadId);
    inv.setAssistantEntryId(assistantEntryId);
    inv.setOrdinal(0);
    inv.setToolCallId("call-" + id);
    inv.setToolName("write");
    inv.setToolVersion("1");
    inv.setLocation("PLATFORM");
    inv.setArgumentsJson("{}");
    inv.setStatus(ToolInvocationStatus.WAITING_APPROVAL.name());
    inv.setPermissionAction("ASK");
    inv.setSideEffect("WRITE");
    inv.setDeadlineAt(now.plusHours(1));
    inv.setCreateTime(now);
    inv.setUpdateTime(now);
    return inv;
  }
}
