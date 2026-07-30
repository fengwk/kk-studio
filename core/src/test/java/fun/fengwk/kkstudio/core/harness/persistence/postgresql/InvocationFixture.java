package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Creates valid queued/running Invocation rows so tests can isolate one target constraint. */
final class InvocationFixture {

  private InvocationFixture() {}

  static long insertQueuedModel(ThreadFixture thread, long executionEpoch) throws SQLException {
    long invocationId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_model_invocation (id, thread_id,"
                    + " source_head_entry_id, execution_epoch, request, status, attempt) values"
                    + " (?, ?, ?, ?, '{\"model\":\"stub\"}'::jsonb, 'QUEUED', 1)")) {
      ps.setLong(1, invocationId);
      ps.setLong(2, thread.threadId);
      ps.setLong(3, thread.rootEntryId);
      ps.setLong(4, executionEpoch);
      assertEquals(1, ps.executeUpdate());
    }
    return invocationId;
  }

  static void startModel(long invocationId, String workerToken) throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'RUNNING', worker_token = ?,"
                    + " worker_until = current_timestamp + interval '1 minute', started_at ="
                    + " current_timestamp, deadline_at = current_timestamp + interval '1 hour',"
                    + " last_activity_at = current_timestamp where id = ?")) {
      ps.setString(1, workerToken);
      ps.setLong(2, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  static long insertQueuedTool(
      ThreadFixture thread,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String location,
      String environmentName,
      long executionEpoch)
      throws SQLException {
    long invocationId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertQueuedTool(
          conn,
          invocationId,
          thread,
          assistantEntryId,
          ordinal,
          toolCallId,
          location,
          environmentName,
          executionEpoch);
    }
    return invocationId;
  }

  static void insertQueuedTool(
      Connection conn,
      long invocationId,
      ThreadFixture thread,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String location,
      String environmentName,
      long executionEpoch)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_tool_invocation (id, thread_id, session_id,"
                + " assistant_entry_id, ordinal, tool_call_id, descriptor, arguments,"
                + " location, environment_name, execution_epoch, status, attempt) values"
                + " (?, ?, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?, ?, ?, 'QUEUED', 1)")) {
      ps.setLong(1, invocationId);
      ps.setLong(2, thread.threadId);
      ps.setLong(3, thread.sessionId);
      ps.setLong(4, assistantEntryId);
      ps.setInt(5, ordinal);
      ps.setString(6, toolCallId);
      ps.setString(7, location);
      ps.setString(8, environmentName);
      ps.setLong(9, executionEpoch);
      assertEquals(1, ps.executeUpdate());
    }
  }

  static void startTool(long invocationId, String workerToken) throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set status = 'RUNNING', worker_token = ?,"
                    + " worker_until = current_timestamp + interval '1 minute', started_at ="
                    + " current_timestamp, deadline_at = current_timestamp + interval '1 hour',"
                    + " last_activity_at = current_timestamp, permission_state = 'ALLOWED'"
                    + " where id = ?")) {
      ps.setString(1, workerToken);
      ps.setLong(2, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
