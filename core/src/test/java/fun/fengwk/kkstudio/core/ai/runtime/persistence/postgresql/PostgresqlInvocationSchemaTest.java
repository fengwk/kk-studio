package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Verifies Model/Tool ownership, environment-name, lease, retry, terminal and apply invariants. */
class PostgresqlInvocationSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void modelQueuedRejectsWorkerLease() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = InvocationFixture.insertQueuedModel(thread, 1L);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_queued",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set worker_token = 'worker', worker_until ="
                        + " current_timestamp + interval '1 minute' where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void modelInvocationHeadEntryMustExist() throws SQLException {
    ThreadFixture threadOwner = createThread();

    // The source_head_entry_id points at a globally-unknown entry id; the
    // single-column FK on harness_entry(id) rejects the row.
    long missingEntryId = FIXTURE_IDS.incrementAndGet() + 100_000_000L;
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_invocation_head",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_model_invocation (id, thread_id,"
                        + " source_head_entry_id, execution_epoch, request, status, attempt)"
                        + " values (?, ?, ?, 1, '{}'::jsonb, 'QUEUED', 1)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, threadOwner.threadId);
              ps.setLong(3, missingEntryId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void modelRunningRequiresExecutionOwnershipAndClock() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = InvocationFixture.insertQueuedModel(thread, 1L);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_running",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'RUNNING' where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_worker_token",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'RUNNING', worker_token ="
                        + " '   ', worker_until = current_timestamp + interval '1 minute',"
                        + " started_at = current_timestamp, deadline_at = current_timestamp +"
                        + " interval '1 hour', last_activity_at = current_timestamp where id ="
                        + " ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }

    InvocationFixture.startModel(invocationId, "model-worker");
  }

  @Test
  void modelRetryWaitRequiresAnExistingExecutionClock() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = InvocationFixture.insertQueuedModel(thread, 2L);
    InvocationFixture.startModel(invocationId, "model-worker-1");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_retry_wait",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'RETRY_WAIT', worker_token ="
                        + " null, worker_until = null where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'RETRY_WAIT', next_attempt_at ="
                    + " current_timestamp + interval '5 seconds', worker_token = null,"
                    + " worker_until = null where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void modelTerminalPayloadAndAppliedMarkerMatchStatus() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = InvocationFixture.insertQueuedModel(thread, 3L);
    InvocationFixture.startModel(invocationId, "model-worker-success");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_succeeded_payload",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'SUCCEEDED', finished_at ="
                        + " current_timestamp, worker_token = null, worker_until = null where"
                        + " id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_terminal",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'SUCCEEDED', result ="
                        + " '{\"ok\":true}'::jsonb, worker_token = null, worker_until = null"
                        + " where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'SUCCEEDED', result ="
                    + " '{\"ok\":true}'::jsonb, finished_at = current_timestamp, worker_token ="
                    + " null, worker_until = null where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }

    long appliedId = InvocationFixture.insertQueuedModel(thread, 4L);
    InvocationFixture.startModel(appliedId, "model-worker-applied");
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'SUCCEEDED', result ="
                    + " '{\"ok\":true}'::jsonb, finished_at = current_timestamp, applied_at ="
                    + " current_timestamp, worker_token = null, worker_until = null where id ="
                    + " ?")) {
      ps.setLong(1, appliedId);
      assertEquals(1, ps.executeUpdate());
    }

    long queuedId = InvocationFixture.insertQueuedModel(thread, 5L);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_applied",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set applied_at = current_timestamp where"
                        + " id = ?")) {
              ps.setLong(1, queuedId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void modelFailedRequiresErrorWithoutResult() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = InvocationFixture.insertQueuedModel(thread, 6L);
    InvocationFixture.startModel(invocationId, "model-worker-failure");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_failed_payload",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'FAILED', finished_at ="
                        + " current_timestamp, worker_token = null, worker_until = null where"
                        + " id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'FAILED', error ="
                    + " '{\"kind\":\"timeout\"}'::jsonb, finished_at = current_timestamp,"
                    + " worker_token = null, worker_until = null where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void toolInvocationAssistantEntryMustBelongToCarrierSession() throws SQLException {
    ThreadFixture threadOwner = createThread();
    ThreadFixture otherSession = createThread();
    long assistantId = appendAssistant(threadOwner);
    long modelInvocationId = InvocationFixture.insertQueuedModel(threadOwner, 1L);

    // Assistant Entry is in threadOwner.session but the carrier session_id is
    // otherSession.session, so (session_id, assistant_entry_id) cannot pair on
    // harness_entry (session_id, id).
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_tool_invocation_assistant",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_tool_invocation (id, thread_id, session_id,"
                        + " assistant_entry_id, model_invocation_id, ordinal, tool_call_id, descriptor, arguments,"
                        + " environment_name, execution_epoch, status, attempt)"
                        + " values (?, ?, ?, ?, ?, 0, 'cross-session', '{}'::jsonb, '{}'::jsonb,"
                        + " null, 1, 'QUEUED', 1)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, threadOwner.threadId);
              ps.setLong(3, otherSession.sessionId);
              ps.setLong(4, assistantId);
              ps.setLong(5, modelInvocationId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void toolInvocationModelMustBelongToTheSameThread() throws SQLException {
    ThreadFixture toolOwner = createThread();
    ThreadFixture modelOwner = createThread();
    long assistantId = appendAssistant(toolOwner);
    long foreignModelInvocationId = InvocationFixture.insertQueuedModel(modelOwner, 1L);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_tool_invocation_model",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_tool_invocation (id, thread_id, session_id,"
                        + " assistant_entry_id, model_invocation_id, ordinal, tool_call_id, descriptor, arguments,"
                        + " environment_name, execution_epoch, status, attempt)"
                        + " values (?, ?, ?, ?, ?, 0, 'cross-thread-model', '{}'::jsonb,"
                        + " '{}'::jsonb, null, 1, 'QUEUED', 1)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, toolOwner.threadId);
              ps.setLong(3, toolOwner.sessionId);
              ps.setLong(4, assistantId);
              ps.setLong(5, foreignModelInvocationId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void toolRetryWaitReusesTheExistingExecutionClock() throws SQLException {
    ThreadFixture thread = createThread();
    long assistantId = appendAssistant(thread);
    long invocationId =
        InvocationFixture.insertQueuedTool(thread, assistantId, 0, "lifecycle", null, 1L);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_running",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set status = 'RUNNING' where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_worker_token",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set status = 'RUNNING', worker_token ="
                        + " '   ', worker_until = current_timestamp + interval '1 minute',"
                        + " started_at = current_timestamp, deadline_at = current_timestamp +"
                        + " interval '1 hour', last_activity_at = current_timestamp where id ="
                        + " ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }

    InvocationFixture.startTool(invocationId, "tool-worker-1");
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_retry_wait",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set status = 'RETRY_WAIT', worker_token ="
                        + " null, worker_until = null where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set status = 'RETRY_WAIT', next_attempt_at ="
                    + " current_timestamp + interval '5 seconds', worker_token = null,"
                    + " worker_until = null where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set status = 'RUNNING', attempt = 2,"
                    + " next_attempt_at = null, worker_token = 'tool-worker-2', worker_until ="
                    + " current_timestamp + interval '1 minute', last_activity_at ="
                    + " current_timestamp where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set status = 'SUCCEEDED', result ="
                    + " '{\"content\":[]}'::jsonb, finished_at = current_timestamp,"
                    + " worker_token = null, worker_until = null where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void toolEnvironmentNameAllowsNullOrNonBlankAndRejectsBlank() throws SQLException {
    ThreadFixture thread = createThread();
    long assistantId = appendAssistant(thread);

    // null environment_name is the PLATFORM / unset-route carrier.
    InvocationFixture.insertQueuedTool(thread, assistantId, 0, "platform", null, 1L);
    // a non-blank environment_name is the ENVIRONMENT carrier.
    InvocationFixture.insertQueuedTool(thread, assistantId, 1, "environment", "prod", 1L);

    // Empty or non-canonical surrounding whitespace violates the durable route constraint.
    assertInvalidToolEnvironmentName(thread, assistantId, 2, "");
    assertInvalidToolEnvironmentName(thread, assistantId, 3, "   ");
    assertInvalidToolEnvironmentName(thread, assistantId, 4, "\t");
    assertInvalidToolEnvironmentName(thread, assistantId, 5, "\n");
    assertInvalidToolEnvironmentName(thread, assistantId, 6, "\tprod\t");
  }

  @Test
  void toolOrdinalIsDurableIdentityWhileToolCallIdRemainsProviderData() throws SQLException {
    ThreadFixture thread = createThread();
    long assistantId = appendAssistant(thread);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_tool_call_id",
          () ->
              InvocationFixture.insertQueuedTool(
                  conn, FIXTURE_IDS.incrementAndGet(), thread, assistantId, 0, "   ", null, 1L));
    }
    InvocationFixture.insertQueuedTool(thread, assistantId, 0, "provider-call", null, 1L);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_tool_invocation_source",
          () ->
              InvocationFixture.insertQueuedTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  assistantId,
                  0,
                  "different-provider-call",
                  null,
                  1L));
    }
    InvocationFixture.insertQueuedTool(thread, assistantId, 1, "provider-call", null, 1L);
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private long appendAssistant(ThreadFixture thread) throws SQLException {
    return thread.appendChild("MESSAGE");
  }

  private void assertInvalidToolEnvironmentName(
      ThreadFixture thread, long assistantId, int ordinal, String environmentName)
      throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_environment_name",
          () ->
              InvocationFixture.insertQueuedTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  assistantId,
                  ordinal,
                  "invalid-environment-" + ordinal,
                  environmentName,
                  1L));
    }
  }
}
