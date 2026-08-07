package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Session + ROOT Entry + Thread 绑定到该 ROOT 的确定性 id 集合。
 *
 * <p>Session 与 Thread 是相互独立的聚合：先插入 Session/Entry 行，Thread 通过单列 {@code head_entry_id} FK 引用
 * ROOT。Entry helper 保证 fixture 构建确定性，不依赖随机 id 或时间派生的值。
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

  /** 从共享计数器分配一个新的 fixture；root 与 thread 都是确定性的。 */
  static ThreadFixture fresh() {
    long s = PostgresSchemaSupport.FIXTURE_IDS.addAndGet(1_000L);
    long t = s + 100L;
    long r = s + 200L;
    return new ThreadFixture(s, t, r);
  }

  /** 分配并持久化一个全新的 Session/ROOT/已绑定 Thread 组合。 */
  static ThreadFixture insertFresh() throws SQLException {
    ThreadFixture fixture = fresh();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      fixture.insertAtomically(conn);
    }
    return fixture;
  }

  /** 使用给定连接插入 Session、ROOT Entry 与绑定到该 ROOT 的 Thread。 */
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
              "insert into harness_thread (id, head_entry_id, yolo_enabled,"
                  + " next_command_sequence, revision, created_at, updated_at)"
                  + " values (?, ?, false, 1, 0, current_timestamp, current_timestamp)")) {
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

  /** 在 ROOT 下追加一个非 ROOT Entry（例如用于 assistant 链的 MESSAGE）。 */
  void insertChildEntry(Connection conn, long entryId, String type) throws SQLException {
    insertChildEntry(conn, entryId, rootEntryId, type);
  }

  /** 分配并追加一个 ROOT 下的子 Entry，返回其 id。 */
  long appendChild(String type) throws SQLException {
    long entryId = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertChildEntry(conn, entryId, type);
    }
    return entryId;
  }

  /** 以显式 parent id 追加一个 Entry（例如 Assistant MESSAGE 链）。 */
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
