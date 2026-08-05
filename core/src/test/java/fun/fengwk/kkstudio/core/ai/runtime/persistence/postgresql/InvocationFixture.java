package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Creates valid Model/Tool Invocation rows so tests can isolate one target constraint.
 *
 * <p>Ids are allocated by the caller via {@code harness_runtime_id_seq} semantics (any unique
 * positive value); the schema enforces positivity itself. Every model invocation gets a fresh
 * {@code turn_start_entry_id} so the {@code uk_harness_model_invocation_turn} uniqueness never
 * interferes with fixture reuse.
 */
final class InvocationFixture {

  private InvocationFixture() {}

  /** Insert a READY model invocation bound to the fixture Thread's ROOT entry. */
  static long insertModel(ThreadFixture thread) throws SQLException {
    long invocationId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertModel(
          conn,
          invocationId,
          thread,
          thread.rootEntryId,
          thread.rootEntryId,
          "{\"model\":\"stub\"}",
          null,
          null,
          null,
          "READY",
          0,
          null);
    }
    return invocationId;
  }

  /**
   * Insert a model invocation with explicit facts. JSONB arguments are raw JSON text or {@code
   * null} (nullable columns); {@code status} and {@code attempt} are caller-controlled so each test
   * can violate exactly one constraint.
   */
  static void insertModel(
      Connection conn,
      long invocationId,
      ThreadFixture thread,
      long turnStartEntryId,
      long basisHeadEntryId,
      String requestJson,
      String streamCheckpointJson,
      String resultJson,
      String errorJson,
      String status,
      int attempt,
      Long resultEntryId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_model_invocation (id, thread_id, turn_start_entry_id,"
                + " basis_head_entry_id, request, status, attempt, stream_checkpoint, result,"
                + " error, result_entry_id, created_at, updated_at) values"
                + " (?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb),"
                + " cast(? as jsonb), ?, current_timestamp, current_timestamp)")) {
      ps.setLong(1, invocationId);
      ps.setLong(2, thread.threadId);
      ps.setLong(3, turnStartEntryId);
      ps.setLong(4, basisHeadEntryId);
      ps.setString(5, requestJson);
      ps.setString(6, status);
      ps.setInt(7, attempt);
      ps.setString(8, streamCheckpointJson);
      ps.setString(9, resultJson);
      ps.setString(10, errorJson);
      if (resultEntryId == null) {
        ps.setNull(11, Types.BIGINT);
      } else {
        ps.setLong(11, resultEntryId);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** Insert a READY tool invocation under an existing model invocation and assistant Entry. */
  static long insertTool(
      ThreadFixture thread, long modelInvocationId, long assistantEntryId, int ordinal)
      throws SQLException {
    long invocationId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertTool(
          conn,
          invocationId,
          modelInvocationId,
          assistantEntryId,
          ordinal,
          "{\"tool\":\"stub\"}",
          null,
          null,
          null,
          "READY",
          0,
          null);
    }
    return invocationId;
  }

  /**
   * Insert a tool invocation with explicit facts. JSONB arguments are raw JSON text or {@code null}
   * (nullable columns); {@code status} and {@code attempt} are caller-controlled so each test can
   * violate exactly one constraint.
   */
  static void insertTool(
      Connection conn,
      long invocationId,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String requestJson,
      String approvalJson,
      String resultJson,
      String errorJson,
      String status,
      int attempt,
      Long resultEntryId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id,"
                + " ordinal, request, status, attempt, approval, result, error, result_entry_id,"
                + " created_at, updated_at) values (?, ?, ?, ?, cast(? as jsonb), ?, ?,"
                + " cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, current_timestamp,"
                + " current_timestamp)")) {
      ps.setLong(1, invocationId);
      ps.setLong(2, modelInvocationId);
      ps.setLong(3, assistantEntryId);
      ps.setInt(4, ordinal);
      ps.setString(5, requestJson);
      ps.setString(6, status);
      ps.setInt(7, attempt);
      ps.setString(8, approvalJson);
      ps.setString(9, resultJson);
      ps.setString(10, errorJson);
      if (resultEntryId == null) {
        ps.setNull(11, Types.BIGINT);
      } else {
        ps.setLong(11, resultEntryId);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }
}
