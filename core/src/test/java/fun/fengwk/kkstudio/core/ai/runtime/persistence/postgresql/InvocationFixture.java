package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

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
      String environmentName,
      long executionEpoch)
      throws SQLException {
    long candidateModelInvocationId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (PreparedStatement model =
        conn.prepareStatement(
            "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
                + " execution_epoch, request, status, attempt) values"
                + " (?, ?, ?, ?, '{\"model\":\"stub\"}'::jsonb, 'QUEUED', 1)"
                + " on conflict (thread_id, source_head_entry_id, execution_epoch) do nothing")) {
      model.setLong(1, candidateModelInvocationId);
      model.setLong(2, thread.threadId);
      model.setLong(3, thread.rootEntryId);
      model.setLong(4, executionEpoch);
      model.executeUpdate();
    }
    long modelInvocationId;
    try (PreparedStatement model =
        conn.prepareStatement(
            "select id from harness_model_invocation where thread_id = ?"
                + " and source_head_entry_id = ? and execution_epoch = ?")) {
      model.setLong(1, thread.threadId);
      model.setLong(2, thread.rootEntryId);
      model.setLong(3, executionEpoch);
      try (var result = model.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("model invocation fixture row was not created");
        }
        modelInvocationId = result.getLong(1);
      }
    }
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_tool_invocation (id, thread_id, session_id,"
                + " assistant_entry_id, model_invocation_id, ordinal, tool_call_id, descriptor, arguments,"
                + " environment_name, execution_epoch, status, attempt) values"
                + " (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?, ?, 'QUEUED', 1)")) {
      ps.setLong(1, invocationId);
      ps.setLong(2, thread.threadId);
      ps.setLong(3, thread.sessionId);
      ps.setLong(4, assistantEntryId);
      ps.setLong(5, modelInvocationId);
      ps.setInt(6, ordinal);
      ps.setString(7, toolCallId);
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
