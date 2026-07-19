package fun.fengwk.kkstudio.core.harness.thread.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.harness.task.service.DatabaseTaskRuntime;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions.BeginTurnResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions.BeginTurnStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * beginTurn vs cancelTree must linearize on the child Thread row: either admit one TURN_STARTED
 * before cancellation, or reject after CANCELLED — never admit after CANCELLED is observed.
 *
 * <p>Not class-level {@code @Transactional}: concurrent workers need independent commits.
 */
@SpringBootTest
class BeginTurnCancelTreeConcurrencyTest {

  @Autowired private ThreadTransactions transactions;
  @Autowired private DatabaseTaskRuntime taskRuntime;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;

  @Test
  void beginTurnAndCancelTreeLinearizeOnChildThread() throws Exception {
    long threadId = 8_801_201L;
    long parentInvocationId = threadId + 1_000;
    String token = "owner-race";
    // Keep the fixture lease active while cancelTree's after-commit kick runs; this test isolates
    // beginTurn/cancelTree lock linearization rather than activating the processor on a partial
    // row.
    Instant now = Instant.now();
    LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    insertOwnedThread(threadId, token, timestamp);
    insertRunningTask(parentInvocationId, threadId, timestamp);

    int baselineTurns = eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<BeginTurnResult> beginFuture =
          pool.submit(
              () -> {
                start.await();
                return transactions.beginTurn(threadId, token, now);
              });
      Future<?> cancelFuture =
          pool.submit(
              () -> {
                start.await();
                taskRuntime.cancelTree(parentInvocationId, now);
                return null;
              });
      start.countDown();
      BeginTurnResult begin = beginFuture.get(10, TimeUnit.SECONDS);
      cancelFuture.get(10, TimeUnit.SECONDS);

      String status = taskMapper.find(parentInvocationId).getStatus();
      int turns = eventMapper.countByType(threadId, ThreadEventType.TURN_STARTED.value());
      int failures = eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value());

      assertEquals(TaskState.CANCELLED.name(), status);
      if (begin.status() == BeginTurnStatus.ADMITTED) {
        // beginTurn won the Thread lock: TURN_STARTED committed before CANCELLED.
        assertEquals(baselineTurns + 1, turns);
      } else {
        // cancelTree won: beginTurn observes CANCELLED and rejects without TURN_STARTED.
        assertEquals(BeginTurnStatus.REJECTED, begin.status());
        assertTrue(begin.rejectionReason().contains("CANCELLED"));
        assertEquals(baselineTurns, turns);
        assertEquals(1, failures);
      }
    } finally {
      pool.shutdownNow();
    }
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

  private void insertRunningTask(long parentInvocationId, long childThreadId, LocalDateTime now) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setChildSessionId(childThreadId + 100);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(5);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);
  }
}
