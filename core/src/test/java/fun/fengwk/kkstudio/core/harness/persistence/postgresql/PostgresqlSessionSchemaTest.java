package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void sessionRootAndMainThreadBootstrapAtomically() throws SQLException {
    ThreadFixture thread = createThread();

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select s.main_thread_id, e.entry_type from harness_session s"
                    + " join harness_entry e on e.session_id = s.id"
                    + " where s.id = ? and e.id = ?")) {
      ps.setLong(1, thread.sessionId);
      ps.setLong(2, thread.rootEntryId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(thread.threadId, rs.getLong(1));
        assertEquals("ROOT", rs.getString(2));
      }
    }
  }

  @Test
  void mainThreadMustBelongToItsSession() throws SQLException {
    ThreadFixture first = createThread();
    ThreadFixture second = createThread();

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_session_main_thread",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_session set main_thread_id = ? where id = ?")) {
              ps.setLong(1, second.threadId);
              ps.setLong(2, first.sessionId);
              ps.executeUpdate();
            }
          });
    }
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
  void childSessionParentFieldsAreOneConsistentRelation() throws SQLException {
    ThreadFixture parent = createThread();
    ThreadFixture child = createThread();
    ThreadFixture other = createThread();
    long assistantId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      parent.insertChildEntry(conn, assistantId, "MESSAGE");
    }
    long toolId =
        InvocationFixture.insertQueuedTool(
            parent, assistantId, 0, "task-call", "PLATFORM", null, 1L);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_session_parent_pair",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_session set parent_session_id = ? where id = ?")) {
              ps.setLong(1, parent.sessionId);
              ps.setLong(2, child.sessionId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_session_parent_invocation",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_session set parent_session_id = ?, parent_invocation_id = ?"
                        + " where id = ?")) {
              ps.setLong(1, child.sessionId);
              ps.setLong(2, toolId);
              ps.setLong(3, other.sessionId);
              ps.executeUpdate();
            }
          });
    }

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_session set parent_session_id = ?, parent_invocation_id = ?"
                    + " where id = ?")) {
      ps.setLong(1, parent.sessionId);
      ps.setLong(2, toolId);
      ps.setLong(3, child.sessionId);
      assertEquals(1, ps.executeUpdate());
    }
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
  void inputTypesMatchKernelContractWithoutLegacyAlias() throws SQLException {
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
