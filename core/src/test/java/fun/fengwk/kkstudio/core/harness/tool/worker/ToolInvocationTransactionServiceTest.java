package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Stale claim fencing: old lease owner A cannot terminate a row re-leased to B. */
@SpringBootTest
@Transactional
class ToolInvocationTransactionServiceTest {

  @Autowired private ToolInvocationTransactionService transactions;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private HarnessThreadMapper threadMapper;

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
    row.setAgentDefinitionId(1L);
    row.setRuntimeConfigJson("{}");
    row.setYoloEnabled(false);
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
