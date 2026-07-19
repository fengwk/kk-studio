package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Stale claim fencing: old lease owner A cannot terminate a row re-leased to B. */
@SpringBootTest
class ToolInvocationTransactionServiceTest {

  @MockitoBean private ThreadKick threadKick;

  @Autowired private ToolInvocationTransactionService transactions;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private HarnessThreadMapper threadMapper;
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
        base.environmentId(),
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
    inv.setAssistantEntryId(100L);
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
