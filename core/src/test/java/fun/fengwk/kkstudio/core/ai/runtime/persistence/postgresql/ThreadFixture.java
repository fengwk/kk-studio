package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Deterministic ids for a Session + ROOT Entry + Thread bundle bound to that ROOT.
 *
 * <p>Session and Thread are independent aggregates: the Session/Entry rows are inserted first, and
 * the Thread only references the ROOT through the deferrable single-column {@code head_entry_id}
 * FK. Entry helpers keep fixture construction deterministic without random ids or clock-derived
 * values.
 */
final class ThreadFixture {

  final long sessionId;
  final long threadId;
  final long rootEntryId;

  ThreadFixture(long sessionId, long threadId, long rootEntryId) {
    this.sessionId = sessionId;
    this.threadId = threadId;
    this.rootEntryId = rootEntryId;
  }

  /** Allocate a fresh fixture from the shared counter; root and thread are deterministic. */
  static ThreadFixture fresh() {
    long s = PostgresSchemaSupport.FIXTURE_IDS.addAndGet(1_000L);
    long t = s + 100L;
    long r = s + 200L;
    return new ThreadFixture(s, t, r);
  }

  /** Allocate and persist a fresh Session/ROOT/bound Thread bundle. */
  static ThreadFixture insertFresh() throws SQLException {
    ThreadFixture fixture = fresh();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      fixture.insertAtomically(conn);
    }
    return fixture;
  }

  /** Insert Session, ROOT Entry and a Thread bound to that ROOT using the supplied connection. */
  void insertAtomically(Connection conn) throws SQLException {
    Objects.requireNonNull(conn);
    boolean prevAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    try {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into harness_session (id, title, created_at)"
                  + " values (?, 'fixture', current_timestamp)")) {
        ps.setLong(1, sessionId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                  + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb,"
                  + " current_timestamp)")) {
        ps.setLong(1, rootEntryId);
        ps.setLong(2, sessionId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into harness_thread (id, head_entry_id, input_sequence,"
                  + " runnable, execution_epoch, created_at, updated_at)"
                  + " values (?, ?, 0, false, 1, current_timestamp, current_timestamp)")) {
        ps.setLong(1, threadId);
        ps.setLong(2, rootEntryId);
        ps.executeUpdate();
      }
      conn.commit();
    } catch (SQLException | RuntimeException e) {
      conn.rollback();
      throw e;
    } finally {
      if (prevAutoCommit) {
        conn.setAutoCommit(true);
      }
    }
  }

  /** Append a non-ROOT entry under the ROOT (e.g. a MESSAGE for assistant chains). */
  void insertChildEntry(Connection conn, long entryId, String type) throws SQLException {
    insertChildEntry(conn, entryId, rootEntryId, type);
  }

  /** Allocate and append one child Entry below the ROOT, returning its id. */
  long appendChild(String type) throws SQLException {
    long entryId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertChildEntry(conn, entryId, type);
    }
    return entryId;
  }

  /** Append an Entry with an explicit parent id (e.g. an Assistant MESSAGE chain). */
  void insertChildEntry(Connection conn, long entryId, long parentId, String type)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                + " created_at) values (?, ?, ?, ?, '{}'::jsonb, current_timestamp)")) {
      ps.setLong(1, entryId);
      ps.setLong(2, sessionId);
      ps.setLong(3, parentId);
      ps.setString(4, type);
      ps.executeUpdate();
    }
  }
}
