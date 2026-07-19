package fun.fengwk.kkstudio.core.harness.thread.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Real H2 coverage for listRecoverableThreadIds selection predicates. */
@SpringBootTest
@Transactional
class HarnessThreadRecoveryMapperTest {

  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private ToolInvocationMapper invocationMapper;

  @Test
  void selectsExpiredTokenOnlyAndExcludesActiveTokenWithPendingInput() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long expiredId = 7_100L;
    long activeId = 7_101L;
    insertThread(expiredId, "RUNNING", "tok-expired", now.minusSeconds(10), now);
    insertThread(activeId, "RUNNING", "tok-active", now.plusMinutes(5), now);
    insertPendingInput(8_001L, activeId, 1L, now);

    List<Long> ids = threadMapper.listRecoverableThreadIds(now, 100);
    assertTrue(ids.contains(expiredId), "expired processor token must be recoverable alone");
    assertFalse(ids.contains(activeId), "active lease with pending input must not be selected");
  }

  @Test
  void excludesWaitingApprovalOnlyAndSelectsPendingWithoutToken() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long waitingOnly = 7_102L;
    long pendingNoToken = 7_103L;
    insertThread(waitingOnly, "WAITING", null, null, now);
    insertThread(pendingNoToken, "WAITING", null, null, now);
    insertWaitingApproval(9_001L, waitingOnly, 200L, now);
    insertPendingInput(8_002L, pendingNoToken, 1L, now);

    List<Long> ids = threadMapper.listRecoverableThreadIds(now, 100);
    assertFalse(ids.contains(waitingOnly), "WAITING_APPROVAL-only must be excluded");
    assertTrue(ids.contains(pendingNoToken), "pending input without token must be selected");
  }

  /** RETRYING 必须由 recovery 再次 kick，FAILED 则只能等待显式 retry。 */
  @Test
  void selectsRetryingWithoutTokenAndExcludesFailed() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long retryingId = 7_104L;
    long failedId = 7_105L;
    insertThread(retryingId, "RETRYING", null, null, now);
    insertThread(failedId, "FAILED", null, null, now);

    List<Long> ids = threadMapper.listRecoverableThreadIds(now, 100);

    assertTrue(ids.contains(retryingId), "retrying debt must be recoverable");
    assertFalse(ids.contains(failedId), "failed thread must await an explicit retry");
  }

  private void insertThread(
      long id, String status, String token, LocalDateTime processorUntil, LocalDateTime now) {
    HarnessThreadDO row = new HarnessThreadDO();
    row.setId(id);
    row.setSessionId(id + 1000);
    row.setHeadEntryId(id + 2000);
    row.setStatus(status);
    row.setInputSequence(0L);
    row.setProcessorToken(token);
    row.setProcessorUntil(processorUntil);
    row.setVersion(0L);
    row.setCreateTime(now);
    row.setUpdateTime(now);
    // session FK may not be enforced in H2 test schema for this insert path; if required, skip via
    // try/catch of existing helper. Here harness_thread is free of FK in schema-h2 for recovery.
    threadMapper.insert(row);
  }

  private void insertPendingInput(long id, long threadId, long sequence, LocalDateTime now) {
    HarnessThreadInputDO input = new HarnessThreadInputDO();
    input.setId(id);
    input.setThreadId(threadId);
    input.setSequence(sequence);
    input.setInputType("user_message");
    input.setPayloadJson("{\"type\":\"message\",\"role\":\"user\",\"contents\":[]}");
    input.setClientMessageId("cid-" + id);
    input.setStatus("queued");
    input.setCreateTime(now);
    inputMapper.insert(input);
  }

  private void insertWaitingApproval(
      long id, long threadId, long assistantEntryId, LocalDateTime now) {
    ToolInvocationDO inv = new ToolInvocationDO();
    inv.setId(id);
    inv.setThreadId(threadId);
    inv.setAssistantEntryId(assistantEntryId);
    inv.setOrdinal(0);
    inv.setToolCallId("call-" + id);
    inv.setToolName("write");
    inv.setToolVersion("1");
    inv.setTargetType("CONTROL");
    inv.setArgumentsJson("{}");
    inv.setStatus("WAITING_APPROVAL");
    inv.setPermissionAction("ASK");
    inv.setSideEffect("WRITE");
    inv.setDeadlineAt(now.plusHours(1));
    inv.setCreateTime(now);
    inv.setUpdateTime(now);
    invocationMapper.insert(inv);
  }
}
