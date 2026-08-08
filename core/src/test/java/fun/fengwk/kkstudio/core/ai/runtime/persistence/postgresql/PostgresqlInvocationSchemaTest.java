package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 验证 runtime-spring Model/Tool invocation 事实（jsonb 对象载荷、状态列表、terminal 互斥、 entry 标识 FK）以及 {@code
 * harness_work} 目标 lease 队列。
 */
class PostgresqlInvocationSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void modelStatusAcceptsOnlyDeclaredStates() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_status",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  thread.rootEntryId,
                  "{\"model\":\"stub\"}",
                  null,
                  null,
                  null,
                  "QUEUED",
                  0,
                  null));
    }
    InvocationFixture.insertModel(thread);
    long turnStartId = thread.appendChild("TURN_START");
    try (Connection conn = newConnection()) {
      InvocationFixture.insertModel(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread,
          turnStartId,
          thread.rootEntryId,
          "{\"model\":\"stub\"}",
          null,
          null,
          null,
          "RUNNING",
          0,
          null);
    }
  }

  @Test
  void modelJsonbFactsAreObjectsAndTerminalFactsAreMutuallyExclusive() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_model_invocation_request_check",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  thread.rootEntryId,
                  "[1,2]",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_model_invocation_stream_checkpoint_check",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  thread.rootEntryId,
                  "{\"model\":\"stub\"}",
                  "\"scalar\"",
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    long invocationId = InvocationFixture.insertModel(thread);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_terminal_facts",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set status = 'SUCCEEDED', result ="
                        + " '{\"ok\":true}'::jsonb, error = '{\"kind\":\"boom\"}'::jsonb"
                        + " where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_model_invocation_error_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set error = '[1]'::jsonb where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void modelUpdatedAtMustNotMoveBackwards() throws SQLException {
    ThreadFixture thread = createThread();
    long invocationId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      InvocationFixture.insertModel(
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

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_invocation_time_order",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set updated_at = created_at - interval"
                        + " '1 hour' where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void modelTurnStartIsUniquePerThread() throws SQLException {
    ThreadFixture thread = createThread();
    InvocationFixture.insertModel(thread);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_model_invocation_turn",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  thread.rootEntryId,
                  "{\"model\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }

    // 同一 Thread 中不同的 turn start 对应不同的 invocation。
    long turnStartId = thread.appendChild("TURN_START");
    try (Connection conn = newConnection()) {
      InvocationFixture.insertModel(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread,
          turnStartId,
          thread.rootEntryId,
          "{\"model\":\"stub\"}",
          null,
          null,
          null,
          "READY",
          0,
          null);
    }
    assertEquals(
        2L,
        singleLong(
            "select count(*) from harness_model_invocation where thread_id = " + thread.threadId));
  }

  @Test
  void modelEntryForeignKeysRequireExistingEntries() throws SQLException {
    ThreadFixture thread = createThread();
    long missingEntryId = FIXTURE_IDS.incrementAndGet() + 100_000_000L;

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_invocation_turn_start",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  missingEntryId,
                  thread.rootEntryId,
                  "{\"model\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_invocation_basis",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  missingEntryId,
                  "{\"model\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_invocation_result",
          () ->
              InvocationFixture.insertModel(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread,
                  thread.rootEntryId,
                  thread.rootEntryId,
                  "{\"model\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  missingEntryId));
    }
  }

  @Test
  void modelResultEntryIsUniqueWhenSet() throws SQLException {
    ThreadFixture thread = createThread();
    long resultEntryId = thread.appendChild("MESSAGE");
    long firstId = InvocationFixture.insertModel(thread);
    long secondId = FIXTURE_IDS.incrementAndGet();
    long secondTurnStartId = thread.appendChild("TURN_START");
    try (Connection conn = newConnection()) {
      InvocationFixture.insertModel(
          conn,
          secondId,
          thread,
          secondTurnStartId,
          thread.rootEntryId,
          "{\"model\":\"stub\"}",
          null,
          null,
          null,
          "READY",
          0,
          null);
    }

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set result_entry_id = ? where id = ?")) {
      ps.setLong(1, resultEntryId);
      ps.setLong(2, firstId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_model_invocation_result",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_invocation set result_entry_id = ? where id = ?")) {
              ps.setLong(1, resultEntryId);
              ps.setLong(2, secondId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void toolStatusAcceptsOnlyDeclaredStates() throws SQLException {
    ThreadFixture thread = createThread();
    long modelInvocationId = InvocationFixture.insertModel(thread);
    long assistantEntryId = thread.appendChild("MESSAGE");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_status",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  modelInvocationId,
                  assistantEntryId,
                  0,
                  "{\"tool\":\"stub\"}",
                  null,
                  null,
                  null,
                  "QUEUED",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      InvocationFixture.insertTool(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          modelInvocationId,
          assistantEntryId,
          0,
          "{\"tool\":\"stub\"}",
          null,
          null,
          null,
          "WAITING_APPROVAL",
          0,
          null);
    }
  }

  @Test
  void toolJsonbFactsAreObjectsAndTerminalFactsAreMutuallyExclusive() throws SQLException {
    ThreadFixture thread = createThread();
    long modelInvocationId = InvocationFixture.insertModel(thread);
    long assistantEntryId = thread.appendChild("MESSAGE");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_tool_invocation_request_check",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  modelInvocationId,
                  assistantEntryId,
                  0,
                  "[1,2]",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    long invocationId =
        InvocationFixture.insertTool(thread, modelInvocationId, assistantEntryId, 1);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_tool_invocation_effects_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set status = 'SUCCEEDED',"
                        + " result = '{}'::jsonb, effects = '[]'::jsonb where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_effects_status",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set effects ="
                        + " '{\"version\":1,\"customEntries\":[{\"pluginId\":\"goal\"}]}'::jsonb"
                        + " where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_tool_invocation_approval_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set approval = '\"granted\"'::jsonb"
                        + " where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_tool_invocation_terminal_facts",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set status = 'SUCCEEDED', result ="
                        + " '{\"ok\":true}'::jsonb, error = '{\"kind\":\"boom\"}'::jsonb"
                        + " where id = ?")) {
              ps.setLong(1, invocationId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set status = 'SUCCEEDED',"
                    + " result = '{\"ok\":true}'::jsonb,"
                    + " effects ="
                    + " '{\"version\":1,\"customEntries\":[{\"pluginId\":\"goal\"}]}'::jsonb"
                    + " where id = ?")) {
      ps.setLong(1, invocationId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void toolOrdinalIsDurableIdentityWithinAssistant() throws SQLException {
    ThreadFixture thread = createThread();
    long modelInvocationId = InvocationFixture.insertModel(thread);
    long assistantEntryId = thread.appendChild("MESSAGE");
    InvocationFixture.insertTool(thread, modelInvocationId, assistantEntryId, 0);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_tool_invocation_ordinal",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  modelInvocationId,
                  assistantEntryId,
                  0,
                  "{\"tool\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_tool_invocation_ordinal_check",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  modelInvocationId,
                  assistantEntryId,
                  -1,
                  "{\"tool\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
  }

  @Test
  void toolModelAndResultForeignKeysRequireExistingRows() throws SQLException {
    ThreadFixture thread = createThread();
    long modelInvocationId = InvocationFixture.insertModel(thread);
    long assistantEntryId = thread.appendChild("MESSAGE");
    long resultEntryId = thread.appendChild("MESSAGE");
    long missingId = FIXTURE_IDS.incrementAndGet() + 100_000_000L;

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_tool_invocation_model",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  missingId,
                  assistantEntryId,
                  0,
                  "{\"tool\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_tool_invocation_assistant",
          () ->
              InvocationFixture.insertTool(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  modelInvocationId,
                  missingId,
                  0,
                  "{\"tool\":\"stub\"}",
                  null,
                  null,
                  null,
                  "READY",
                  0,
                  null));
    }
    long firstId = InvocationFixture.insertTool(thread, modelInvocationId, assistantEntryId, 0);
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_tool_invocation set result_entry_id = ? where id = ?")) {
      ps.setLong(1, resultEntryId);
      ps.setLong(2, firstId);
      assertEquals(1, ps.executeUpdate());
    }
    long secondId = InvocationFixture.insertTool(thread, modelInvocationId, assistantEntryId, 1);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_tool_invocation_result",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_tool_invocation set result_entry_id = ? where id = ?")) {
              ps.setLong(1, resultEntryId);
              ps.setLong(2, secondId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void workTargetTypeWakeVersionAndTargetIdAreConstrained() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_work_target_type",
          () -> insertWork(conn, "SESSION", 930_001L, 1L, null, null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_work_wake_version_check",
          () -> insertWork(conn, "THREAD", 930_002L, 0L, null, null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_work_target_id_check",
          () -> insertWork(conn, "THREAD", 0L, 1L, null, null));
    }
  }

  @Test
  void workLeaseTokenAndDeadlineMoveTogether() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_work_lease_pair",
          () -> insertWork(conn, "THREAD", 931_001L, 1L, "token", null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_work_lease_pair",
          () -> insertWork(conn, "THREAD", 931_002L, 1L, null, "current_timestamp"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_work_lease_token",
          () -> insertWork(conn, "THREAD", 931_003L, 1L, "   ", "current_timestamp"));
    }
    try (Connection conn = newConnection()) {
      insertWork(conn, "THREAD", 931_004L, 1L, "worker", "current_timestamp");
    }
  }

  @Test
  void workPrimaryKeyIsTheTargetIdentity() throws SQLException {
    try (Connection conn = newConnection()) {
      insertWork(conn, "MODEL", 932_001L, 1L, null, null);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "harness_work_pkey", () -> insertWork(conn, "MODEL", 932_001L, 2L, null, null));
    }
  }

  @Test
  void workIndexesTargetAvailableAndLeaseScans() throws SQLException {
    assertTrue(
        indexDefinition("idx_harness_work_available")
            .contains("(available_at, target_type, target_id)"));
    String lease = indexDefinition("idx_harness_work_lease_until");
    assertTrue(lease.contains("(lease_until, target_type, target_id)"));
    assertTrue(lease.contains("WHERE (lease_until IS NOT NULL)"));
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private static void insertWork(
      Connection conn,
      String targetType,
      long targetId,
      long wakeVersion,
      String leaseToken,
      String leaseUntil)
      throws SQLException {
    String token = leaseToken == null ? "null" : "?";
    String until = leaseUntil == null ? "null" : leaseUntil;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_work (target_type, target_id, available_at, wake_version,"
                + " lease_token, lease_until) values (?, ?, current_timestamp, ?, "
                + token
                + ", "
                + until
                + ")")) {
      ps.setString(1, targetType);
      ps.setLong(2, targetId);
      ps.setLong(3, wakeVersion);
      int index = 4;
      if (leaseToken != null) {
        ps.setString(index++, leaseToken);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static long singleLong(String sql) throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next(), "no row for: " + sql);
      return rs.getLong(1);
    }
  }

  private static String indexDefinition(String indexName) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?")) {
      ps.setString(1, indexName);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "missing index " + indexName);
        return rs.getString(1);
      }
    }
  }
}
