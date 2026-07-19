package fun.fengwk.kkstudio.core.harness.thread.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions.BeginTurnResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions.BeginTurnStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Atomic beginTurn: admit + TURN_STARTED, or reject with failure events and no TURN_STARTED, in one
 * transaction under the owning processor token.
 */
@SpringBootTest
@Transactional
class HarnessThreadBeginTurnTest {

  @Autowired private ThreadTransactions transactions;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;

  @Test
  void runningChildBelowMaxAdmitsAndAppendsTurnStarted() {
    long threadId = 8_800_101L;
    String token = "owner-admit";
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    insertOwnedThread(threadId, token, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTask(threadId, TaskState.RUNNING, 3, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTurnStarted(threadId, 1, LocalDateTime.ofInstant(now, ZoneOffset.UTC));

    BeginTurnResult result = transactions.beginTurn(threadId, token, now);

    assertEquals(BeginTurnStatus.ADMITTED, result.status());
    assertEquals(2, eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value()));
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value()));
  }

  @Test
  void atMaxTurnsRejectsWithFailureEventsAndNoTurnStarted() {
    long threadId = 8_800_102L;
    String token = "owner-max";
    Instant now = Instant.parse("2026-01-01T00:00:01Z");
    insertOwnedThread(threadId, token, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTask(threadId, TaskState.RUNNING, 2, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTurnStarted(threadId, 2, LocalDateTime.ofInstant(now, ZoneOffset.UTC));

    BeginTurnResult result = transactions.beginTurn(threadId, token, now);

    assertEquals(BeginTurnStatus.REJECTED, result.status());
    assertTrue(result.rejectionReason().contains("maxTurns"));
    assertEquals(2, eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.ASSISTANT_FAILED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value()));
  }

  @Test
  void cancelledTaskRejectsWithFailureEventsAndNoTurnStarted() {
    long threadId = 8_800_103L;
    String token = "owner-cancel";
    Instant now = Instant.parse("2026-01-01T00:00:02Z");
    insertOwnedThread(threadId, token, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTask(threadId, TaskState.CANCELLED, 5, LocalDateTime.ofInstant(now, ZoneOffset.UTC));

    BeginTurnResult result = transactions.beginTurn(threadId, token, now);

    assertEquals(BeginTurnStatus.REJECTED, result.status());
    assertTrue(result.rejectionReason().contains("CANCELLED"));
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.ASSISTANT_FAILED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value()));
  }

  @Test
  void wrongTokenIsLostOwnershipWithoutEvents() {
    long threadId = 8_800_104L;
    Instant now = Instant.parse("2026-01-01T00:00:03Z");
    insertOwnedThread(threadId, "owner-a", LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    insertTask(threadId, TaskState.RUNNING, 3, LocalDateTime.ofInstant(now, ZoneOffset.UTC));

    BeginTurnResult result = transactions.beginTurn(threadId, "owner-b", now);

    assertEquals(BeginTurnStatus.LOST_OWNERSHIP, result.status());
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value()));
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value()));
  }

  private void insertOwnedThread(long threadId, String token, LocalDateTime now) {
    HarnessThreadDO row = new HarnessThreadDO();
    row.setId(threadId);
    row.setSessionId(threadId + 10);
    row.setHeadEntryId(threadId + 20);
    row.setStatus("RUNNING");
    row.setInputSequence(0L);
    row.setProcessorToken(token);
    row.setProcessorUntil(now.plusMinutes(5));
    row.setVersion(0L);
    row.setCreateTime(now);
    row.setUpdateTime(now);
    threadMapper.insert(row);
  }

  private void insertTask(long childThreadId, TaskState state, int maxTurns, LocalDateTime now) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(childThreadId + 1_000);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setChildSessionId(childThreadId + 100);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(maxTurns);
    task.setStatus(state.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }

  private void insertTurnStarted(long threadId, int count, LocalDateTime now) {
    for (int i = 0; i < count; i++) {
      HarnessThreadEventDO event = new HarnessThreadEventDO();
      event.setId(threadId * 10 + i + 1);
      event.setThreadId(threadId);
      event.setEventType(ThreadEventType.TURN_STARTED.value());
      event.setPayloadJson("{\"schemaVersion\":1}");
      event.setCreateTime(now.minusSeconds(count - i));
      eventMapper.insert(event);
    }
  }
}
