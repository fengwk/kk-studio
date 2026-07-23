package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Verifies Model/Tool ownership, location, lease, retry, terminal and apply invariants. */
class PostgresqlInvocationSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
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
  void modelThreadAndSourceEntryMustShareSession() throws SQLException {
    ThreadFixture threadOwner = createThread();
    ThreadFixture entryOwner = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_invocation_thread",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_model_invocation (id, thread_id, session_id,"
                        + " source_head_entry_id, execution_epoch, request, status, attempt)"
                        + " values (?, ?, ?, ?, 1, '{}'::jsonb, 'QUEUED', 1)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, threadOwner.threadId);
              ps.setLong(3, entryOwner.sessionId);
              ps.setLong(4, entryOwner.rootEntryId);
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
  void toolThreadAndAssistantEntryMustShareSession() throws SQLException {
    ThreadFixture threadOwner = createThread();
    ThreadFixture entryOwner = createThread();
    long assistantId = appendAssistant(entryOwner);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_tool_invocation_thread",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_tool_invocation (id, thread_id, session_id,"
                        + " assistant_entry_id, ordinal, tool_call_id, descriptor, arguments,"
                        + " location, environment_name, execution_epoch, status, attempt)"
                        + " values (?, ?, ?, ?, 0, 'cross-session', '{}'::jsonb, '{}'::jsonb,"
                        + " 'PLATFORM', null, 1, 'QUEUED', 1)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, threadOwner.threadId);
              ps.setLong(3, entryOwner.sessionId);
              ps.setLong(4, assistantId);
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
        InvocationFixture.insertQueuedTool(
            thread, assistantId, 0, "lifecycle", "PLATFORM", null, 1L);

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
  void toolLocationRequiresExactlyOneValidEnvironmentReference() throws SQLException {
    ThreadFixture thread = createThread();
    long assistantId = appendAssistant(thread);

    assertInvalidToolLocation(thread, assistantId, 0, "ENVIRONMENT", null);
    assertInvalidToolLocation(thread, assistantId, 1, "ENVIRONMENT", "   ");
    InvocationFixture.insertQueuedTool(
        thread, assistantId, 2, "environment", "ENVIRONMENT", "prod", 1L);
    assertInvalidToolLocation(thread, assistantId, 3, "PLATFORM", "prod");
    InvocationFixture.insertQueuedTool(thread, assistantId, 4, "platform", "PLATFORM", null, 1L);
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
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  assistantId,
                  0,
                  "   ",
                  "PLATFORM",
                  null,
                  1L));
    }
    InvocationFixture.insertQueuedTool(
        thread, assistantId, 0, "provider-call", "PLATFORM", null, 1L);
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
                  "PLATFORM",
                  null,
                  1L));
    }
    InvocationFixture.insertQueuedTool(
        thread, assistantId, 1, "provider-call", "PLATFORM", null, 1L);
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private long appendAssistant(ThreadFixture thread) throws SQLException {
    return thread.appendChild("MESSAGE");
  }

  private void assertInvalidToolLocation(
      ThreadFixture thread, long assistantId, int ordinal, String location, String environmentName)
      throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_location_env",
          () ->
              InvocationFixture.insertQueuedTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  assistantId,
                  ordinal,
                  "invalid-location-" + ordinal,
                  location,
                  environmentName,
                  1L));
    }
  }
}
