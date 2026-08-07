package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 验证已绑定的 Session/Entry/Thread schema、command mailbox 完整性以及递归路径。 */
class PostgresqlSessionSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void sessionRootAndThreadUseOnlyTheBoundHeadEntry() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select t.head_entry_id, e.entry_type, e.session_id from harness_thread t"
                    + " join harness_entry e on e.id = t.head_entry_id"
                    + " where t.id = ?")) {
      ps.setLong(1, thread.threadId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(thread.rootEntryId, rs.getLong(1));
        assertEquals("ROOT", rs.getString(2));
        // Thread 的当前 Session 由 head Entry 推导而来，绝不存储在该行上。
        assertEquals(thread.sessionId, rs.getLong(3));
      }
    }
  }

  /** Session 只组织 Entry Tree；Thread 通过 head Entry 推导 Session。 */
  @Test
  void sessionAndThreadNoLongerCarryEachOthersIdentity() throws SQLException {
    assertFalse(columnExists("harness_session", "main_thread_id"));
    assertFalse(columnExists("harness_session", "parent_session_id"));
    assertFalse(columnExists("harness_session", "parent_invocation_id"));
    assertFalse(columnExists("harness_session", "updated_at"));
    assertFalse(columnExists("harness_thread", "session_id"));
    assertFalse(tableExists("chat_session"));
  }

  /** head_entry_id 必填，并且只是指向全局 Entry id 的单列 FK。 */
  @Test
  void threadHeadEntryIsRequiredAndBoundBySingleColumnForeignKey() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select is_nullable from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'harness_thread'"
                    + " and column_name = 'head_entry_id'")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("NO", rs.getString(1));
      }
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_thread_head",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement("update harness_thread set head_entry_id = ? where id = ?")) {
              ps.setLong(1, FIXTURE_IDS.addAndGet(500_000L));
              ps.setLong(2, thread.threadId);
              ps.executeUpdate();
            }
          });
    }

    assertEquals(List.of("head_entry_id"), foreignKeyColumns("fk_harness_thread_head"));
  }

  /** 所有 Harness 执行表只通过单列 FK 引用其直接 owner。 */
  @Test
  void harnessExecutionForeignKeysTargetTheirOwners() throws SQLException {
    assertEquals(List.of("thread_id"), foreignKeyColumns("fk_harness_thread_command_thread"));
    assertEquals(
        List.of("consumed_turn_start_entry_id"),
        foreignKeyColumns("fk_harness_thread_command_consumed"));
    assertEquals(List.of("thread_id"), foreignKeyColumns("fk_harness_model_invocation_thread"));
    assertEquals(
        List.of("turn_start_entry_id"),
        foreignKeyColumns("fk_harness_model_invocation_turn_start"));
    assertEquals(
        List.of("basis_head_entry_id"), foreignKeyColumns("fk_harness_model_invocation_basis"));
    assertEquals(
        List.of("model_invocation_id"), foreignKeyColumns("fk_harness_tool_invocation_model"));
    assertEquals(
        List.of("assistant_entry_id"), foreignKeyColumns("fk_harness_tool_invocation_assistant"));
  }

  @Test
  void entryParentMustBelongToTheSameSession() throws SQLException {
    ThreadFixture first = createThread();
    ThreadFixture second = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_entry_parent",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type,"
                        + " payload, created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb,"
                        + " current_timestamp)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, second.sessionId);
              ps.setLong(3, first.rootEntryId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void entryCannotBeItsOwnParent() throws SQLException {
    ThreadFixture thread = createThread();
    long entryId = FIXTURE_IDS.incrementAndGet();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_entry_parent_not_self",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type,"
                        + " payload, created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb,"
                        + " current_timestamp)")) {
              ps.setLong(1, entryId);
              ps.setLong(2, thread.sessionId);
              ps.setLong(3, entryId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void sessionHasAtMostOneRootEntry() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_entry_single_root",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type,"
                        + " payload, created_at) values (?, ?, null, 'ROOT', '{}'::jsonb,"
                        + " current_timestamp)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, thread.sessionId);
              ps.executeUpdate();
            }
          });
    }

    long siblingId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      thread.insertChildEntry(conn, siblingId, "MESSAGE");
    }
    assertEquals(2L, countEntries(thread.sessionId));
  }

  /** revision/next_command_sequence 是 runtime 所有物；DB 只保证非负/正数和时间顺序。 */
  @Test
  void threadRuntimeStateColumnsAreConstrainedButNeverMutated() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_time_order",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread set updated_at = created_at - interval '1 hour'"
                        + " where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_thread_next_command_sequence_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread set next_command_sequence = 0 where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_thread_revision_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement("update harness_thread set revision = -1 where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_thread_id_check",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_thread (id, head_entry_id, yolo_enabled,"
                        + " next_command_sequence, revision, created_at, updated_at)"
                        + " values (0, ?, false, 1, 0, current_timestamp, current_timestamp)")) {
              ps.setLong(1, thread.rootEntryId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void commandSequenceMustBePositive() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_thread_command_sequence_check",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  0L,
                  "USER_MESSAGE",
                  "zero-sequence",
                  "{}",
                  null,
                  null,
                  null));
    }
  }

  @Test
  void commandPayloadMustBeAJsonObject() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "harness_thread_command_payload_check",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "array-payload",
                  "[1,2]",
                  null,
                  null,
                  null));
    }
  }

  @Test
  void commandTypeAllowsOnlyDeclaredCommands() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      insertCommand(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread.threadId,
          1L,
          "CUSTOM_MESSAGE",
          "custom-message",
          "{}",
          null,
          null,
          null);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_command_type",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  2L,
                  "EXECUTE_POLICY",
                  "removed-alias",
                  "{}",
                  null,
                  null,
                  null));
    }
  }

  @Test
  void commandSequenceAndClientIdAreUniquePerThread() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      insertCommand(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread.threadId,
          1L,
          "USER_MESSAGE",
          "client-a",
          "{}",
          null,
          null,
          null);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_thread_command_sequence",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "client-b",
                  "{}",
                  null,
                  null,
                  null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_thread_command_client",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  2L,
                  "USER_MESSAGE",
                  "client-a",
                  "{}",
                  null,
                  null,
                  null));
    }
  }

  @Test
  void commandConsumedAndCancelledAreMutuallyExclusive() throws SQLException {
    ThreadFixture thread = createThread();
    long turnStartId = thread.appendChild("TURN_START");

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_command_terminal",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "consumed-and-cancelled",
                  "{}",
                  turnStartId,
                  "current_timestamp",
                  null));
    }
  }

  @Test
  void commandCancellationIsNotBackdated() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_command_cancel_time",
          () ->
              insertCommand(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "backdated-cancel",
                  "{}",
                  null,
                  "timestamptz '2024-01-01 00:00:00Z'",
                  "timestamptz '2024-01-02 00:00:00Z'"));
    }
  }

  @Test
  void recursiveCteReturnsRootToLeafPath() throws SQLException {
    ThreadFixture thread = createThread();
    long userId = FIXTURE_IDS.incrementAndGet();
    long assistantId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      thread.insertChildEntry(conn, userId, "MESSAGE");
      thread.insertChildEntry(conn, assistantId, userId, "MESSAGE");
    }

    String cte =
        "with recursive walk(id, session_id, parent_entry_id, entry_type, depth) as ("
            + " select id, session_id, parent_entry_id, entry_type, 0 from harness_entry"
            + " where id = ? and session_id = ?"
            + " union all"
            + " select parent.id, parent.session_id, parent.parent_entry_id, parent.entry_type,"
            + " walk.depth + 1 from harness_entry parent"
            + " join walk on parent.id = walk.parent_entry_id"
            + " where parent.session_id = walk.session_id"
            + ") select id, entry_type from walk order by depth desc";
    List<Long> ids = new ArrayList<>();
    List<String> types = new ArrayList<>();
    try (Connection conn = newConnection();
        PreparedStatement ps = conn.prepareStatement(cte)) {
      ps.setLong(1, assistantId);
      ps.setLong(2, thread.sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getLong(1));
          types.add(rs.getString(2));
        }
      }
    }
    assertEquals(Arrays.asList(thread.rootEntryId, userId, assistantId), ids);
    assertEquals(Arrays.asList("ROOT", "MESSAGE", "MESSAGE"), types);
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private static boolean columnExists(String table, String column) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select 1 from information_schema.columns where table_schema = 'public'"
                    + " and table_name = ? and column_name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static boolean tableExists(String table) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select 1 from information_schema.tables where table_schema = 'public'"
                    + " and table_name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** 按声明顺序返回命名 FK 约束引用的列。 */
  private static List<String> foreignKeyColumns(String constraint) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select a.attname from pg_constraint c"
                    + " join unnest(c.conkey) with ordinality as k(attnum, ord) on true"
                    + " join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum"
                    + " where c.conname = ? and c.contype = 'f' order by k.ord")) {
      ps.setString(1, constraint);
      List<String> columns = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
      assertFalse(columns.isEmpty(), "missing foreign key constraint: " + constraint);
      return columns;
    }
  }

  private void insertCommand(
      Connection conn,
      long commandId,
      long threadId,
      long sequence,
      String commandType,
      String clientCommandId,
      String payloadJson,
      Long consumedTurnStartEntryId,
      String cancelledAt,
      String createdAt)
      throws SQLException {
    String consumed = consumedTurnStartEntryId == null ? "null" : "?";
    String cancel = cancelledAt == null ? "null" : cancelledAt;
    String created = createdAt == null ? "current_timestamp" : createdAt;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_thread_command (id, thread_id, sequence, command_type, payload,"
                + " client_command_id, consumed_turn_start_entry_id, cancelled_at, created_at)"
                + " values (?, ?, ?, ?, cast(? as jsonb), ?, "
                + consumed
                + ", "
                + cancel
                + ", "
                + created
                + ")")) {
      ps.setLong(1, commandId);
      ps.setLong(2, threadId);
      ps.setLong(3, sequence);
      ps.setString(4, commandType);
      ps.setString(5, payloadJson);
      ps.setString(6, clientCommandId);
      int index = 7;
      if (consumedTurnStartEntryId != null) {
        ps.setLong(index++, consumedTurnStartEntryId);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  private long countEntries(long sessionId) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select count(*) from harness_entry where session_id = ?")) {
      ps.setLong(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getLong(1);
      }
    }
  }
}
