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

/** Verifies Session/Entry/Thread bootstrap, mailbox integrity and recursive path loading. */
class PostgresqlSessionSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
    }
  }

  @Test
  void sessionRootBootstrapsIndependentlyAndThreadOnlyReferencesItsHeadEntry() throws SQLException {
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
        // The Thread's current Session is derived from the head Entry, never stored on the row.
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

  /** head_entry_id 可空（UNBOUND Thread），并且只是指向全局 Entry id 的单列 FK。 */
  @Test
  void threadHeadEntryIsNullableAndBoundBySingleColumnForeignKey() throws SQLException {
    long unbound = ThreadFixture.insertUnboundThread();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select head_entry_id, execution_epoch from harness_thread where id = ?")) {
      ps.setLong(1, unbound);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        rs.getLong(1);
        assertTrue(rs.wasNull(), "UNBOUND thread must persist a null head_entry_id");
        assertEquals(0L, rs.getLong(2));
      }
    }

    // A Thread may point at any Entry in the global id space, including another Session's tree.
    ThreadFixture other = createThread();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_thread set head_entry_id = ? where id = ?")) {
      ps.setLong(1, other.rootEntryId);
      ps.setLong(2, unbound);
      assertEquals(1, ps.executeUpdate());
    }

    // The single-column FK still rejects a head that does not exist at all.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_thread_head",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement("update harness_thread set head_entry_id = ? where id = ?")) {
              ps.setLong(1, FIXTURE_IDS.addAndGet(500_000L));
              ps.setLong(2, unbound);
              ps.executeUpdate();
            }
          });
    }

    assertEquals(List.of("head_entry_id"), foreignKeyColumns("fk_harness_thread_head"));
  }

  /** Model/Tool/Usage 只用单列 thread_id FK 引用 Thread，不再假设 Thread 终身属于一个 Session。 */
  @Test
  void invocationAndUsageThreadForeignKeysAreSingleColumn() throws SQLException {
    assertEquals(List.of("thread_id"), foreignKeyColumns("fk_harness_model_invocation_thread"));
    assertEquals(List.of("thread_id"), foreignKeyColumns("fk_harness_tool_invocation_thread"));
    assertEquals(List.of("thread_id"), foreignKeyColumns("fk_harness_model_usage_thread"));
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
                        + " payload) values (?, ?, ?, 'MESSAGE', '{}'::jsonb)")) {
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
                        + " payload) values (?, ?, ?, 'MESSAGE', '{}'::jsonb)")) {
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
                        + " payload) values (?, ?, null, 'ROOT', '{}'::jsonb)")) {
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

  @Test
  void processorLeaseTokenAndDeadlineMoveTogether() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_lease_pair",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread set processor_token = 'owner' where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_lease_token",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread set processor_token = '   ', processor_until ="
                        + " current_timestamp + interval '1 minute' where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_thread set processor_token = 'owner', processor_until ="
                    + " current_timestamp + interval '1 minute' where id = ?")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_lease_pair",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread set processor_token = null where id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void inputAppliedTimeMatchesStatusAndCreationTime() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_applied",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "missing-applied-at",
                  "APPLIED"));
    }

    long inputId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertInputWithoutAppliedAt(
          conn, inputId, thread.threadId, 1L, "USER_MESSAGE", "queued-input", "QUEUED");
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_applied",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread_input set status = 'CANCELLED', applied_at ="
                        + " current_timestamp where id = ?")) {
              ps.setLong(1, inputId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_thread_input set status = 'APPLIED', applied_at ="
                    + " current_timestamp where id = ?")) {
      ps.setLong(1, inputId);
      assertEquals(1, ps.executeUpdate());
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_time_order",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_thread_input (id, thread_id, sequence, input_type,"
                        + " payload, idempotency_key, status, created_at, applied_at) values"
                        + " (?, ?, 2, 'USER_MESSAGE', '{}'::jsonb, 'backdated', 'APPLIED',"
                        + " timestamptz '2024-01-02 00:00:00Z',"
                        + " timestamptz '2024-01-01 00:00:00Z')")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void inputTypesMatchRuntimeContractWithoutLegacyAlias() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      insertInputWithoutAppliedAt(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread.threadId,
          1L,
          "SET_YOLO",
          "kernel-yolo",
          "QUEUED");
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_type",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  2L,
                  "SET_EXECUTION_POLICY",
                  "removed-alias",
                  "QUEUED"));
    }
  }

  @Test
  void inputSequenceAndIdempotencyKeyAreUniquePerThread() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_idempotency",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "   ",
                  "QUEUED"));
    }
    try (Connection conn = newConnection()) {
      insertInputWithoutAppliedAt(
          conn,
          FIXTURE_IDS.incrementAndGet(),
          thread.threadId,
          1L,
          "USER_MESSAGE",
          "client-a",
          "QUEUED");
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_thread_input_sequence",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  1L,
                  "USER_MESSAGE",
                  "client-b",
                  "QUEUED"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_thread_input_idempotency",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  2L,
                  "USER_MESSAGE",
                  "client-a",
                  "QUEUED"));
    }
  }

  @Test
  void inputSequenceMustBePositive() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_input_sequence_pos",
          () ->
              insertInputWithoutAppliedAt(
                  conn,
                  FIXTURE_IDS.incrementAndGet(),
                  thread.threadId,
                  0L,
                  "USER_MESSAGE",
                  "zero-sequence",
                  "QUEUED"));
    }
  }

  @Test
  void recursiveCteReturnsRootToLeafPath() throws SQLException {
    ThreadFixture thread = createThread();
    long configId = FIXTURE_IDS.incrementAndGet();
    long userId = FIXTURE_IDS.incrementAndGet();
    long assistantId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      thread.insertChildEntry(conn, configId, "RUNTIME_CONFIG");
      thread.insertChildEntry(conn, userId, configId, "MESSAGE");
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
    assertEquals(Arrays.asList(thread.rootEntryId, configId, userId, assistantId), ids);
    assertEquals(Arrays.asList("ROOT", "RUNTIME_CONFIG", "MESSAGE", "MESSAGE"), types);
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

  /** Referencing columns of a named FK constraint, in declaration order. */
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

  private void insertInputWithoutAppliedAt(
      Connection conn,
      long inputId,
      long threadId,
      long sequence,
      String inputType,
      String idempotencyKey,
      String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_thread_input (id, thread_id, sequence, input_type, payload,"
                + " idempotency_key, status) values (?, ?, ?, ?, '{}'::jsonb, ?, ?)")) {
      ps.setLong(1, inputId);
      ps.setLong(2, threadId);
      ps.setLong(3, sequence);
      ps.setString(4, inputType);
      ps.setString(5, idempotencyKey);
      ps.setString(6, status);
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
