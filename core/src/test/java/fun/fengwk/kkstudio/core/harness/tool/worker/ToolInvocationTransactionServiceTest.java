package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Stale claim fencing: old lease owner A cannot terminate a row re-leased to B. */
@SpringBootTest
class ToolInvocationTransactionServiceTest {

  @MockitoBean private ThreadKick threadKick;

  @Autowired private ToolInvocationTransactionService transactions;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread");
    reset(threadKick);
  }

  @Test
  void staleClaimOwnerCannotTerminateReLeasedRow() {
    long id = 9_001_001L;
    long threadId = 42L;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(threadId, now);
    ToolInvocationDO row = baseRunning(id, threadId, "owner-b", now.plusMinutes(5), now);
    invocationMapper.insert(row);

    ToolInvocation staleA =
        withLease(invocationStore.find(id).orElseThrow(), "owner-a", Instant.now().plusSeconds(30));
    ClaimedToolInvocation claimedA = new ClaimedToolInvocation(staleA, false);

    assertFalse(transactions.start(claimedA, Instant.now()));
    assertFalse(
        transactions.appendPartial(
            claimedA, List.of(ToolResult.error("call-" + id, "should-not-apply")), Instant.now()));

    boolean terminated =
        transactions.terminate(
            claimedA,
            ToolInvocationStatus.SUCCEEDED,
            ToolResult.error("call-" + id, "should-not-apply"),
            "stale",
            Instant.now());
    assertFalse(terminated);

    ToolInvocationDO durable = invocationMapper.find(id);
    assertEquals("RUNNING", durable.getStatus());
    assertEquals("owner-b", durable.getLeaseOwner());
    assertNull(durable.getResultJson());
    assertEquals(0, eventMapper.listAfter(threadId, 0L, 10).size());
    verifyNoMoreInteractions(threadKick);
  }

  /** start 和非空 partial 都持久化为可重放的 Thread event；空 partial 仅确认租约。 */
  @Test
  void startAndPartialsWriteDurableEventsOnlyWhenThereIsContent() {
    long id = 9_001_010L;
    long threadId = 50L;
    Instant now = Instant.now();
    LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    insertThread(threadId, timestamp);
    invocationMapper.insert(
        baseRunning(id, threadId, "owner-a", timestamp.plusMinutes(5), timestamp));
    ClaimedToolInvocation claimed =
        new ClaimedToolInvocation(invocationStore.find(id).orElseThrow(), false);

    assertTrue(transactions.start(claimed, now));
    assertTrue(transactions.appendPartial(claimed, null, now));
    assertTrue(transactions.appendPartial(claimed, List.of(), now));
    assertTrue(
        transactions.appendPartial(
            claimed, List.of(ToolResult.error("call-" + id, "first delta")), now));

    List<HarnessThreadEventDO> events = eventMapper.listAfter(threadId, 0L, 10);
    assertEquals(2, events.size());
    assertEquals(ThreadEventType.TOOL_STARTED.value(), events.get(0).getEventType());
    assertTrue(events.get(0).getPayloadJson().contains("\"invocationId\":\"" + id + "\""));
    assertEquals(ThreadEventType.TOOL_DELTA_BATCH.value(), events.get(1).getEventType());
    assertTrue(events.get(1).getPayloadJson().contains("first delta"));
    verifyNoMoreInteractions(threadKick);
  }

  /** 所有外部终态都落库，结果（若有）先以 delta 交付，再追加 completed。 */
  @Test
  void failureAndCancellationTerminateWithDurableCompletionEvents() {
    Instant now = Instant.now();
    LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    long failureId = 9_001_011L;
    long cancellationId = 9_001_012L;
    long failureThreadId = 51L;
    long cancellationThreadId = 52L;
    insertThread(failureThreadId, timestamp);
    insertThread(cancellationThreadId, timestamp);
    invocationMapper.insert(
        baseRunning(failureId, failureThreadId, "owner-a", timestamp.plusMinutes(5), timestamp));
    invocationMapper.insert(
        baseRunning(
            cancellationId, cancellationThreadId, "owner-b", timestamp.plusMinutes(5), timestamp));

    assertTrue(
        transactions.terminate(
            new ClaimedToolInvocation(invocationStore.find(failureId).orElseThrow(), false),
            ToolInvocationStatus.FAILED,
            ToolResult.error("call-" + failureId, "failed"),
            "failed",
            now));
    assertTrue(
        transactions.terminate(
            new ClaimedToolInvocation(invocationStore.find(cancellationId).orElseThrow(), false),
            ToolInvocationStatus.CANCELLED,
            null,
            "cancelled",
            now));

    assertEquals(ToolInvocationStatus.FAILED.name(), invocationMapper.find(failureId).getStatus());
    assertEquals(
        ToolInvocationStatus.CANCELLED.name(), invocationMapper.find(cancellationId).getStatus());
    List<HarnessThreadEventDO> failureEvents = eventMapper.listAfter(failureThreadId, 0L, 10);
    assertEquals(2, failureEvents.size());
    assertEquals(ThreadEventType.TOOL_DELTA_BATCH.value(), failureEvents.get(0).getEventType());
    assertEquals(ThreadEventType.TOOL_COMPLETED.value(), failureEvents.get(1).getEventType());
    assertTrue(failureEvents.get(1).getPayloadJson().contains("\"status\":\"FAILED\""));
    List<HarnessThreadEventDO> cancellationEvents =
        eventMapper.listAfter(cancellationThreadId, 0L, 10);
    assertEquals(1, cancellationEvents.size());
    assertEquals(ThreadEventType.TOOL_COMPLETED.value(), cancellationEvents.get(0).getEventType());
    assertTrue(cancellationEvents.get(0).getPayloadJson().contains("\"status\":\"CANCELLED\""));
    verify(threadKick).kick(failureThreadId);
    verify(threadKick).kick(cancellationThreadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** 已持久化为终态的 invocation 可由重试 worker 幂等观察，并重新唤醒等待父 Thread。 */
  @Test
  void alreadyTerminalInvocationIsIdempotentAndKicksWithoutAppendingEvents() {
    long id = 9_001_013L;
    long threadId = 53L;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(threadId, now);
    ToolInvocationDO terminal = baseRunning(id, threadId, "owner-a", now.plusMinutes(5), now);
    terminal.setStatus(ToolInvocationStatus.SUCCEEDED.name());
    terminal.setFinishedAt(now);
    invocationMapper.insert(terminal);

    assertTrue(
        transactions.terminate(
            new ClaimedToolInvocation(invocationStore.find(id).orElseThrow(), true),
            ToolInvocationStatus.SUCCEEDED,
            ToolResult.error("call-" + id, "ignored"),
            "ignored",
            Instant.now()));

    assertEquals(ToolInvocationStatus.SUCCEEDED.name(), invocationMapper.find(id).getStatus());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(threadId).getStatus());
    assertEquals(0, eventMapper.listAfter(threadId, 0L, 10).size());
    verify(threadKick).kick(threadId);
    verifyNoMoreInteractions(threadKick);
  }

  /** 已删除的 invocation 返回 false；持久化不一致的缺失 Thread 显式失败，避免静默写孤儿事件。 */
  @Test
  void missingInvocationReturnsFalseAndMissingOwningThreadFails() {
    long missingInvocationId = 9_001_014L;
    long existingThreadId = 54L;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(existingThreadId, now);
    invocationMapper.insert(
        baseRunning(missingInvocationId, existingThreadId, "owner-a", now.plusMinutes(5), now));
    ClaimedToolInvocation missing =
        new ClaimedToolInvocation(invocationStore.find(missingInvocationId).orElseThrow(), false);
    jdbc.update("delete from tool_invocation where id = ?", missingInvocationId);

    assertFalse(transactions.start(missing, Instant.now()));
    assertFalse(transactions.appendPartial(missing, List.of(), Instant.now()));
    assertFalse(
        transactions.terminate(
            missing, ToolInvocationStatus.UNKNOWN, null, "missing", Instant.now()));

    long orphanInvocationId = 9_001_015L;
    invocationMapper.insert(
        baseRunning(orphanInvocationId, 55L, "owner-a", now.plusMinutes(5), now));
    ClaimedToolInvocation orphan =
        new ClaimedToolInvocation(invocationStore.find(orphanInvocationId).orElseThrow(), false);
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> transactions.start(orphan, Instant.now()));
    assertTrue(error.getMessage().contains("owning thread missing"));
    verifyNoMoreInteractions(threadKick);
  }

  /**
   * terminal Tool result converts the externally waiting Thread to RUNNING before the after-commit
   * kick.
   */
  @Test
  void terminalResultPromotesWaitingThreadAndKicksAfterCommit() {
    long id = 9_001_002L;
    long threadId = 43L;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    insertThread(threadId, now);
    invocationMapper.insert(baseRunning(id, threadId, "owner-a", now.plusMinutes(5), now));
    ClaimedToolInvocation claimed =
        new ClaimedToolInvocation(invocationStore.find(id).orElseThrow(), false);

    boolean terminated =
        transactions.terminate(
            claimed,
            ToolInvocationStatus.SUCCEEDED,
            ToolResult.error("call-" + id, "completed"),
            null,
            Instant.now());

    assertTrue(terminated);
    assertEquals(ToolInvocationStatus.SUCCEEDED.name(), invocationMapper.find(id).getStatus());
    assertEquals(ThreadStatus.RUNNING.name(), threadMapper.find(threadId).getStatus());
    verify(threadKick).kick(threadId);
    verifyNoMoreInteractions(threadKick);
  }

  private static ToolInvocation withLease(ToolInvocation base, String owner, Instant until) {
    return new ToolInvocation(
        base.id(),
        base.threadId(),
        base.assistantEntryId(),
        base.ordinal(),
        base.toolCallId(),
        base.toolName(),
        base.toolVersion(),
        base.targetType(),
        base.environmentName(),
        base.argumentsJson(),
        base.status(),
        base.permissionAction(),
        base.permissionDecision(),
        base.sideEffect(),
        base.deadlineAt(),
        owner,
        until,
        base.cancelRequestedAt(),
        base.resultJson(),
        base.errorMessage(),
        base.createdAt(),
        base.startedAt(),
        base.finishedAt(),
        base.updatedAt());
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

  private static ToolInvocationDO baseRunning(
      long id, long threadId, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    ToolInvocationDO inv = new ToolInvocationDO();
    inv.setId(id);
    inv.setThreadId(threadId);
    inv.setAssistantEntryId(id + 100L);
    inv.setOrdinal(0);
    inv.setToolCallId("call-" + id);
    inv.setToolName("noop");
    inv.setToolVersion("1");
    inv.setTargetType("CONTROL");
    inv.setArgumentsJson("{}");
    inv.setStatus("RUNNING");
    inv.setPermissionAction("ALLOW");
    inv.setSideEffect("READ_ONLY");
    inv.setDeadlineAt(now.plusHours(1));
    inv.setLeaseOwner(owner);
    inv.setLeaseUntil(leaseUntil);
    inv.setCreateTime(now);
    inv.setStartedAt(now);
    inv.setUpdateTime(now);
    return inv;
  }
}
