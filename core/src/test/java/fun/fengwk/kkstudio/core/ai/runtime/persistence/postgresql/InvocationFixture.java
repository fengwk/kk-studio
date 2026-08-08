package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 创建合法的 Model/Tool Invocation 行，使测试能单独隔离目标约束。
 *
 * <p>id 由调用方按 {@code harness_runtime_id_seq} 语义分配（任意唯一正值）；正数性约束由 schema 自身强制。每次 model invocation
 * 都获得一个全新的 {@code turn_start_entry_id}，从而不会因 {@code uk_harness_model_invocation_turn} 唯一约束而干扰
 * fixture 复用。
 */
final class InvocationFixture {

  private InvocationFixture() {}

  /** 插入一条绑定到 fixture Thread ROOT entry 的 READY model invocation。 */
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
   * 以显式事实插入一条 model invocation。JSONB 参数是原始 JSON 文本或 {@code null}（可空列）； {@code status} 与 {@code
   * attempt} 由调用方控制，以便每个测试仅违反一条约束。
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

  /** 在已存在的 model invocation 与 assistant Entry 下插入 READY tool invocation。 */
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
   * 以显式事实插入一条 tool invocation。JSONB 参数是原始 JSON 文本或 {@code null}（可空列）； {@code status} 与 {@code
   * attempt} 由调用方控制，以便每个测试仅违反一条约束。
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
                + " ordinal, request, status, attempt, approval, result, effects, error,"
                + " result_entry_id, created_at, updated_at) values (?, ?, ?, ?, cast(? as jsonb),"
                + " ?, ?, cast(? as jsonb), cast(? as jsonb),"
                + " '{\"version\":1,\"customEntries\":[]}'::jsonb, cast(? as jsonb), ?,"
                + " current_timestamp, current_timestamp)")) {
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
