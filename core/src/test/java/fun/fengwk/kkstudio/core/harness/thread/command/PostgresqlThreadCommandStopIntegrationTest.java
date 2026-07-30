package fun.fengwk.kkstudio.core.harness.thread.command;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** PostgreSQL stop fencing、transaction rollback 与 command serialization 的状态矩阵。 */
class PostgresqlThreadCommandStopIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  /** Stop 只终结尚未执行的 Invocation，RUNNING 依赖 epoch fence 丢失 ownership。 */
  @Test
  void stopClearsProcessorLeaseAndCancelsOnlySafeInvocationStates() throws Exception {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "stop-matrix", BASE);
    long sessionId = boot.sessionId();
    long threadId = boot.threadId();
    long epoch = boot.executionEpoch();
    long rootEntryId = boot.rootEntryId();
    transactions.enqueue(threadId, userPayload("queued"), "input-1", epoch, BASE);
    seedProcessorLease(threadId);

    long modelQueued = 90_001L;
    long modelRetry = 90_002L;
    long modelRunning = 90_003L;
    insertEntry(sessionId, rootEntryId, 91_001L);
    insertEntry(sessionId, rootEntryId, 91_002L);
    insertEntry(sessionId, rootEntryId, 91_003L);
    insertModel(modelQueued, threadId, 91_001L, epoch, "QUEUED");
    insertModel(modelRetry, threadId, 91_002L, epoch, "RETRY_WAIT");
    insertModel(modelRunning, threadId, 91_003L, epoch, "RUNNING");

    long assistantEntryId = 92_001L;
    insertEntry(sessionId, rootEntryId, assistantEntryId);
    long toolQueued = 93_001L;
    long toolRetry = 93_002L;
    long toolRunning = 93_003L;
    long toolWaitingPermission = 93_004L;
    insertTool(toolQueued, threadId, sessionId, assistantEntryId, 0, epoch, "QUEUED");
    insertTool(toolRetry, threadId, sessionId, assistantEntryId, 1, epoch, "RETRY_WAIT");
    insertTool(toolRunning, threadId, sessionId, assistantEntryId, 2, epoch, "RUNNING");
    insertTool(
        toolWaitingPermission,
        threadId,
        sessionId,
        assistantEntryId,
        3,
        epoch,
        "WAITING_INTERACTION");
    insertTarget("MODEL_INVOCATION", modelQueued);
    insertTarget("MODEL_INVOCATION", modelRetry);
    insertTarget("MODEL_INVOCATION", modelRunning);
    insertTarget("TOOL_INVOCATION", toolQueued);
    insertTarget("TOOL_INVOCATION", toolRetry);
    insertTarget("TOOL_INVOCATION", toolRunning);
    insertTarget("TOOL_INVOCATION", toolWaitingPermission);
    insertOpenToolPermissionInteraction(94_001L, toolWaitingPermission);

    ThreadCommandTransactions.StopResult result =
        transactions.stop(threadId, epoch, BASE.plusSeconds(4));

    assertEquals(epoch + 1, result.executionEpoch());
    assertEquals(1, result.cancelledInputs().size());
    ThreadState thread = threadState(threadId);
    assertEquals(epoch + 1, thread.executionEpoch());
    assertFalse(thread.runnable());
    assertNull(thread.processorToken());
    assertNull(thread.processorUntil());

    assertSafelyCancelled(modelState(modelQueued));
    assertSafelyCancelled(modelState(modelRetry));
    assertStillRunning(modelState(modelRunning), "model-running");
    assertSafelyCancelled(toolState(toolQueued));
    assertSafelyCancelled(toolState(toolRetry));
    assertSafelyCancelled(toolState(toolWaitingPermission));
    assertEquals("PENDING", toolPermissionState(toolWaitingPermission));
    assertStillRunning(toolState(toolRunning), "tool-running");
    assertEquals(0L, targetCount("THREAD", threadId));
    assertEquals(0L, targetCount("MODEL_INVOCATION", modelQueued));
    assertEquals(0L, targetCount("MODEL_INVOCATION", modelRetry));
    assertEquals(0L, targetCount("MODEL_INVOCATION", modelRunning));
    assertEquals(0L, targetCount("TOOL_INVOCATION", toolQueued));
    assertEquals(0L, targetCount("TOOL_INVOCATION", toolRetry));
    assertEquals(0L, targetCount("TOOL_INVOCATION", toolRunning));
    assertEquals(0L, targetCount("TOOL_INVOCATION", toolWaitingPermission));
    assertEquals("CANCELLED", interactionStatus(94_001L));
  }

  /** Input insert 被数据库拒绝时，先发生的 sequence/runnable 更新必须一起回滚。 */
  @Test
  void enqueueRollsBackThreadMutationWhenInputInsertIsSuppressed() throws Exception {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "rollback", BASE);
    long threadId = boot.threadId();
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create function suppress_thread_input_insert() returns trigger
          language plpgsql as $$
          begin
            return null;
          end
          $$
          """);
      statement.execute(
          """
          create trigger suppress_thread_input_insert
          before insert on harness_thread_input
          for each row execute function suppress_thread_input_insert()
          """);
    }

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.enqueue(
                threadId, userPayload("rollback"), "rollback", boot.executionEpoch(), BASE));

    ThreadState thread = threadState(threadId);
    assertEquals(0L, thread.inputSequence());
    assertFalse(thread.runnable());
    assertEquals(0L, inputCount(threadId));
    assertEquals(0L, targetCount("THREAD", threadId));
  }

  /** 不存在的 idempotency key 也必须持有 Thread 行锁，串行化随后 live snapshot resolve。 */
  @Test
  void missingIdempotencyLookupSerializesOnThreadLock() throws Exception {
    long threadId = TestThreads.bootstrap(transactions, "lock", BASE).threadId();
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      status -> {
                        assertTrue(transactions.findExistingInput(threadId, "same-key").isEmpty());
                        firstLocked.countDown();
                        await(releaseFirst);
                        return true;
                      }));
      assertTrue(firstLocked.await(5, TimeUnit.SECONDS));

      var second =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      status -> transactions.findExistingInput(threadId, "same-key").isEmpty()));
      try {
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
      } finally {
        releaseFirst.countDown();
      }
      assertTrue(first.get(5, TimeUnit.SECONDS));
      assertTrue(second.get(5, TimeUnit.SECONDS));
    }
  }

  private static void seedProcessorLease(long threadId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_thread set runnable = true, processor_token = 'processor',"
                    + " processor_until = ? where id = ?")) {
      statement.setTimestamp(1, Timestamp.from(BASE.plusSeconds(30)));
      statement.setLong(2, threadId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void insertTarget(String kind, long id) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_execution_target (target_kind, target_id, available_at)"
                    + " values (?, ?, ?)")) {
      statement.setString(1, kind);
      statement.setLong(2, id);
      statement.setTimestamp(3, Timestamp.from(BASE));
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void insertEntry(long sessionId, long parentEntryId, long entryId)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)")) {
      statement.setLong(1, entryId);
      statement.setLong(2, sessionId);
      statement.setLong(3, parentEntryId);
      statement.setTimestamp(4, Timestamp.from(BASE));
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void insertModel(
      long id, long threadId, long sourceEntryId, long epoch, String status) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_model_invocation (id, thread_id,"
                    + " source_head_entry_id, execution_epoch, request, status, attempt, created_at)"
                    + " values (?, ?, ?, ?, '{}'::jsonb, 'QUEUED', 1, ?)")) {
      statement.setLong(1, id);
      statement.setLong(2, threadId);
      statement.setLong(3, sourceEntryId);
      statement.setLong(4, epoch);
      statement.setTimestamp(5, Timestamp.from(BASE));
      assertEquals(1, statement.executeUpdate());
    }
    transitionInvocation("harness_model_invocation", id, status, "model-running");
  }

  private static void insertTool(
      long id,
      long threadId,
      long sessionId,
      long assistantEntryId,
      int ordinal,
      long epoch,
      String status)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id,"
                    + " ordinal, tool_call_id, descriptor, arguments, location, execution_epoch,"
                    + " status, attempt, created_at) values (?, ?, ?, ?, ?, ?, '{}'::jsonb,"
                    + " '{}'::jsonb, 'PLATFORM', ?, 'QUEUED', 1, ?)")) {
      statement.setLong(1, id);
      statement.setLong(2, threadId);
      statement.setLong(3, sessionId);
      statement.setLong(4, assistantEntryId);
      statement.setInt(5, ordinal);
      statement.setString(6, "call-" + id);
      statement.setLong(7, epoch);
      statement.setTimestamp(8, Timestamp.from(BASE));
      assertEquals(1, statement.executeUpdate());
    }
    transitionInvocation("harness_tool_invocation", id, status, "tool-running");
  }

  private static void insertOpenToolPermissionInteraction(long interactionId, long toolInvocationId)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                insert into harness_interaction (
                    id, owner_kind, owner_id, handler_type, request, status, version, created_at
                ) values (?, 'TOOL_INVOCATION', ?, 'tool-permission', '{}'::jsonb, 'OPEN', 0, ?)
                """)) {
      statement.setLong(1, interactionId);
      statement.setLong(2, toolInvocationId);
      statement.setTimestamp(3, Timestamp.from(BASE));
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void transitionInvocation(String table, long id, String status, String workerToken)
      throws SQLException {
    if ("QUEUED".equals(status)) {
      return;
    }
    String allowedPermissionAssignment =
        "harness_tool_invocation".equals(table) ? " permission_state = 'ALLOWED'," : "";
    String sql;
    if ("RETRY_WAIT".equals(status)) {
      sql =
          "update "
              + table
              + " set status = 'RETRY_WAIT',"
              + allowedPermissionAssignment
              + " started_at = ?, deadline_at = ?,"
              + " last_activity_at = ?, next_attempt_at = ? where id = ?";
    } else if ("RUNNING".equals(status)) {
      sql =
          "update "
              + table
              + " set status = 'RUNNING',"
              + allowedPermissionAssignment
              + " started_at = ?, deadline_at = ?,"
              + " last_activity_at = ?, worker_token = ?, worker_until = ? where id = ?";
    } else if ("WAITING_INTERACTION".equals(status) && "harness_tool_invocation".equals(table)) {
      sql =
          "update harness_tool_invocation"
              + " set status = 'WAITING_INTERACTION', permission_state = 'ASKED' where id = ?";
    } else {
      throw new IllegalArgumentException("unsupported status: " + status);
    }
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      if ("WAITING_INTERACTION".equals(status)) {
        statement.setLong(1, id);
      } else {
        statement.setTimestamp(1, Timestamp.from(BASE.plusSeconds(1)));
        statement.setTimestamp(2, Timestamp.from(BASE.plusSeconds(10)));
        statement.setTimestamp(3, Timestamp.from(BASE.plusSeconds(2)));
      }
      if ("RETRY_WAIT".equals(status)) {
        statement.setTimestamp(4, Timestamp.from(BASE.plusSeconds(3)));
        statement.setLong(5, id);
      } else if ("RUNNING".equals(status)) {
        statement.setString(4, workerToken);
        statement.setTimestamp(5, Timestamp.from(BASE.plusSeconds(8)));
        statement.setLong(6, id);
      }
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static ThreadState threadState(long threadId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select input_sequence, runnable, execution_epoch, processor_token, processor_until"
                    + " from harness_thread where id = ?")) {
      statement.setLong(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return new ThreadState(
            result.getLong(1),
            result.getBoolean(2),
            result.getLong(3),
            result.getString(4),
            result.getTimestamp(5));
      }
    }
  }

  private static long inputCount(long threadId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from harness_thread_input where thread_id = ?")) {
      statement.setLong(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static long targetCount(String kind, long id) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from harness_execution_target where target_kind = ? and target_id = ?")) {
      statement.setString(1, kind);
      statement.setLong(2, id);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static InvocationState modelState(long invocationId) throws SQLException {
    return invocationState("harness_model_invocation", invocationId);
  }

  private static InvocationState toolState(long invocationId) throws SQLException {
    return invocationState("harness_tool_invocation", invocationId);
  }

  private static String toolPermissionState(long invocationId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select permission_state from harness_tool_invocation where id = ?")) {
      statement.setLong(1, invocationId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private static String interactionStatus(long interactionId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select status from harness_interaction where id = ?")) {
      statement.setLong(1, interactionId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private static InvocationState invocationState(String table, long invocationId)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, worker_token, worker_until, next_attempt_at, finished_at from "
                    + table
                    + " where id = ?")) {
      statement.setLong(1, invocationId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return new InvocationState(
            result.getString(1),
            result.getString(2),
            result.getTimestamp(3),
            result.getTimestamp(4),
            result.getTimestamp(5));
      }
    }
  }

  private static void assertSafelyCancelled(InvocationState state) {
    assertEquals("CANCELLED", state.status());
    assertNull(state.workerToken());
    assertNull(state.workerUntil());
    assertNull(state.nextAttemptAt());
    assertNotNull(state.finishedAt());
  }

  private static void assertStillRunning(InvocationState state, String workerToken) {
    assertEquals("RUNNING", state.status());
    assertEquals(workerToken, state.workerToken());
    assertNotNull(state.workerUntil());
    assertNull(state.nextAttemptAt());
    assertNull(state.finishedAt());
  }

  private static RuntimeEntryInputPayload userPayload(String content) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.<AgentMessageContent>of(new TextMessageContent(content)))));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for latch");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for latch", error);
    }
  }

  private record ThreadState(
      long inputSequence,
      boolean runnable,
      long executionEpoch,
      String processorToken,
      Timestamp processorUntil) {}

  private record InvocationState(
      String status,
      String workerToken,
      Timestamp workerUntil,
      Timestamp nextAttemptAt,
      Timestamp finishedAt) {}
}
