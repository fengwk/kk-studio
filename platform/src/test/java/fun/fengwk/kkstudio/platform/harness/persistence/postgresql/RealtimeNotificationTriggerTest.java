package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实时失效触发器的 PostgreSQL 行为契约：只有事务提交后才发出 NOTIFY，且 payload 必须是浏览器可以直接失效对应资源的真实键。
 *
 * <p>触发器等价的三个事实源分别验证：Thread 行按真实执行根聚合（子代理写不推进 root 行版本也仍然聚合到 root）、 ToolInvocation
 * 只在进入/离开待处理时通知并同样聚合到真实 root、EnvironmentConnection 按每次提交的租约写入通知。
 * 观察者连接与写入连接相互独立，因此“未提交/回滚不通知”是被直接观测的，而不是被假设的。
 */
class RealtimeNotificationTriggerTest extends PostgresSchemaSupport {

  private static final String TREE_CHANNEL = "harness_thread_tree";
  private static final String INTERACTION_CHANNEL = "harness_tool_interaction";
  private static final String ENVIRONMENT_CHANNEL = "environment_changed";

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  /** 执行树通知：root 自身与任意深度子代理的写入都聚合到真实 root，普通字段写、回滚与无关状态写保持静默。 */
  @Test
  void executionTreeNotificationsAggregateToTheTrueRoot() throws Exception {
    try (ChannelListener listener = new ChannelListener(TREE_CHANNEL);
        Connection writer = newConnection()) {
      writer.setAutoCommit(false);

      ThreadFixture root = insertRootThread(writer);
      listener.assertSilent();
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1));

      ThreadFixture child = insertChildThread(writer, root);
      ThreadFixture grandchild = insertChildThread(writer, child);
      listener.assertSilent();
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1), "同一事务内的子代理插入只提示真实执行根");

      updateThreadVersion(writer, grandchild.threadId(), 1L);
      writer.commit();
      assertEquals(
          List.of(root.threadId().toString()), listener.await(1), "子代理版本变化必须聚合到 root，而不是子代理自身");

      updateThreadVersion(writer, root.threadId(), 1L);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1));

      updateThreadName(writer, child.threadId());
      writer.commit();
      listener.assertSilent();

      insertRootThread(writer);
      writer.rollback();
      listener.assertSilent();
    }
  }

  /** 交互通知：pending 的进入/离开与 pending 行删除都聚合到真实 root，终态迁移与终态行删除保持静默。 */
  @Test
  void interactionNotificationsTrackPendingEnterAndLeaveAtTheTrueRoot() throws Exception {
    try (ChannelListener listener = new ChannelListener(INTERACTION_CHANNEL);
        Connection writer = newConnection()) {
      writer.setAutoCommit(false);
      ThreadFixture root = insertRootThread(writer);
      ThreadFixture child = insertChildThread(writer, root);
      ThreadFixture grandchild = insertChildThread(writer, child);
      UUID grandchildAssistantEntry = insertEntry(writer, grandchild, "MESSAGE");
      UUID grandchildInvocation = insertModelInvocation(writer, grandchild);
      UUID rootAssistantEntry = insertEntry(writer, root, "MESSAGE");
      UUID rootInvocation = insertModelInvocation(writer, root);
      writer.commit();
      listener.assertSilent();

      UUID pending =
          insertToolInvocation(
              writer, grandchildInvocation, grandchildAssistantEntry, "WAITING_APPROVAL", 0);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1), "孙线程的待审批必须失效真实执行根");

      updateToolStatus(writer, pending, "READY", false);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1), "离开待审批同样要失效真实执行根");

      updateToolStatus(writer, pending, "RUNNING", false);
      writer.commit();
      listener.assertSilent();

      updateToolStatus(writer, pending, "WAITING_INPUT", true);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1));

      updateToolStatus(writer, pending, "SUCCEEDED", false);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1));

      deleteToolInvocation(writer, pending);
      writer.commit();
      listener.assertSilent();

      UUID rootPending =
          insertToolInvocation(writer, rootInvocation, rootAssistantEntry, "WAITING_INPUT", 0);
      writer.commit();
      assertEquals(List.of(root.threadId().toString()), listener.await(1), "root 自身的待处理直接以自身为失效键");

      insertToolInvocation(writer, rootInvocation, rootAssistantEntry, "WAITING_APPROVAL", 1);
      listener.assertSilent();
      writer.rollback();
      listener.assertSilent();
    }
  }

  /** 环境通知：连接行的每次提交写（含心跳续租）都提示同一 environmentId，回滚与未提交写保持静默。 */
  @Test
  void environmentConnectionNotificationsFollowCommittedLeaseWrites() throws Exception {
    try (ChannelListener listener = new ChannelListener(ENVIRONMENT_CHANNEL);
        Connection writer = newConnection()) {
      writer.setAutoCommit(false);
      UUID environmentId = insertEnvironment(writer);
      listener.assertSilent();
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1), "仅注册（尚无连接）的环境也必须进入列表失效提示");

      insertConnection(writer, environmentId, "CONNECTING", null);
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1));

      renewConnectionLease(writer, environmentId);
      writer.commit();
      assertEquals(
          List.of(environmentId.toString()), listener.await(1), "心跳续租既是租约维护周期，也是浏览器重读权威卡片的提示");

      markConnectionReady(writer, environmentId);
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1));

      renameEnvironment(writer, environmentId);
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1));

      deleteConnection(writer, environmentId);
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1), "断线删除必须提示对应 env 卡片失效");

      deleteEnvironment(writer, environmentId);
      writer.commit();
      assertEquals(List.of(environmentId.toString()), listener.await(1), "环境注销必须提示对应 env 卡片失效");

      UUID pendingEnvironmentId = insertEnvironment(writer);
      insertConnection(writer, pendingEnvironmentId, "CONNECTING", null);
      listener.assertSilent();
      writer.rollback();
      listener.assertSilent();
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  /** 一个 Thread fixture：Thread 自身 id、所属 Session、可复用的 head Entry 与它所属执行树的真实 root。 */
  private record ThreadFixture(
      UUID threadId, UUID sessionId, UUID headEntryId, UUID rootThreadId) {}

  private static UUID uuid() {
    return new UUID(0L, FIXTURE_IDS.incrementAndGet());
  }

  private ThreadFixture insertRootThread(Connection conn) throws SQLException {
    UUID sessionId = uuid();
    try (PreparedStatement session =
        conn.prepareStatement(
            "insert into harness_session (id, name, created_at)"
                + " values (?, ?, current_timestamp)")) {
      session.setObject(1, sessionId);
      session.setString(2, "session-" + sessionId);
      assertEquals(1, session.executeUpdate());
    }
    UUID rootEntryId = insertEntry(conn, sessionId, null, "ROOT");
    UUID threadId = uuid();
    insertThreadRow(conn, threadId, sessionId, rootEntryId, null, null);
    return new ThreadFixture(threadId, sessionId, rootEntryId, threadId);
  }

  private ThreadFixture insertChildThread(Connection conn, ThreadFixture parent)
      throws SQLException {
    UUID threadId = uuid();
    insertThreadRow(
        conn,
        threadId,
        parent.sessionId(),
        parent.headEntryId(),
        parent.threadId(),
        parent.rootThreadId());
    return new ThreadFixture(
        threadId, parent.sessionId(), parent.headEntryId(), parent.rootThreadId());
  }

  private void insertThreadRow(
      Connection conn,
      UUID threadId,
      UUID sessionId,
      UUID headEntryId,
      UUID parentThreadId,
      UUID yoloRootThreadId)
      throws SQLException {
    String yoloMode = parentThreadId == null ? "DISABLE" : "FOLLOW";
    try (PreparedStatement thread =
        conn.prepareStatement(
            "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id,"
                + " creation_request_hash, name, yolo_mode, yolo_root_thread_id, execution_control,"
                + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNABLE', 0, 1, 0, current_timestamp,"
                + " current_timestamp)")) {
      thread.setObject(1, threadId);
      thread.setObject(2, sessionId);
      thread.setObject(3, parentThreadId);
      thread.setObject(4, headEntryId);
      thread.setString(5, "0".repeat(64));
      thread.setString(6, "realtime-notify-test-thread");
      thread.setString(7, yoloMode);
      thread.setObject(8, yoloRootThreadId);
      assertEquals(1, thread.executeUpdate());
    }
  }

  private UUID insertEntry(Connection conn, ThreadFixture thread, String entryType)
      throws SQLException {
    return insertEntry(conn, thread.sessionId(), thread.headEntryId(), entryType);
  }

  private UUID insertEntry(Connection conn, UUID sessionId, UUID parentEntryId, String entryType)
      throws SQLException {
    UUID entryId = uuid();
    try (PreparedStatement entry =
        conn.prepareStatement(
            "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                + " created_at) values (?, ?, ?, ?, '{}'::jsonb, current_timestamp)")) {
      entry.setObject(1, entryId);
      entry.setObject(2, sessionId);
      entry.setObject(3, parentEntryId);
      entry.setString(4, entryType);
      assertEquals(1, entry.executeUpdate());
    }
    return entryId;
  }

  private UUID insertModelInvocation(Connection conn, ThreadFixture thread) throws SQLException {
    UUID turnStartEntryId = insertEntry(conn, thread, "TURN_START");
    UUID invocationId = uuid();
    try (PreparedStatement invocation =
        conn.prepareStatement(
            "insert into harness_model_invocation (id, thread_id, turn_start_entry_id,"
                + " request_head_entry_id, request_spec, status, attempt, failed_attempts,"
                + " created_at, updated_at)"
                + " values (?, ?, ?, ?, '{}'::jsonb, 'READY', 0, '[]'::jsonb, current_timestamp,"
                + " current_timestamp)")) {
      invocation.setObject(1, invocationId);
      invocation.setObject(2, thread.threadId());
      invocation.setObject(3, turnStartEntryId);
      invocation.setObject(4, thread.headEntryId());
      assertEquals(1, invocation.executeUpdate());
    }
    return invocationId;
  }

  private UUID insertToolInvocation(
      Connection conn, UUID modelInvocationId, UUID assistantEntryId, String status, int callIndex)
      throws SQLException {
    UUID invocationId = uuid();
    try (PreparedStatement invocation =
        conn.prepareStatement(
            "insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id,"
                + " call_index, call, binding, status, attempt, effects, created_at, updated_at)"
                + " values (?, ?, ?, ?, '{\"name\":\"read\"}'::jsonb, cast(? as jsonb), ?, 0,"
                + " '{\"version\": 1, \"customEntries\": []}'::jsonb, current_timestamp,"
                + " current_timestamp)")) {
      invocation.setObject(1, invocationId);
      invocation.setObject(2, modelInvocationId);
      invocation.setObject(3, assistantEntryId);
      invocation.setInt(4, callIndex);
      invocation.setString(5, "WAITING_INPUT".equals(status) ? "{}" : null);
      invocation.setString(6, status);
      assertEquals(1, invocation.executeUpdate());
    }
    return invocationId;
  }

  private static void updateToolStatus(
      Connection conn, UUID invocationId, String status, boolean binding) throws SQLException {
    try (PreparedStatement update =
        conn.prepareStatement(
            "update harness_tool_invocation set status = ?, binding = cast(? as jsonb), effects ="
                + " '{\"version\": 1, \"customEntries\": []}'::jsonb, updated_at = current_timestamp"
                + " where id = ?")) {
      update.setString(1, status);
      update.setString(2, binding ? "{}" : null);
      update.setObject(3, invocationId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private static void deleteToolInvocation(Connection conn, UUID invocationId) throws SQLException {
    try (PreparedStatement delete =
        conn.prepareStatement("delete from harness_tool_invocation where id = ?")) {
      delete.setObject(1, invocationId);
      assertEquals(1, delete.executeUpdate());
    }
  }

  private static void updateThreadVersion(Connection conn, UUID threadId, long version)
      throws SQLException {
    try (PreparedStatement update =
        conn.prepareStatement("update harness_thread set version = ? where id = ?")) {
      update.setLong(1, version);
      update.setObject(2, threadId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private static void updateThreadName(Connection conn, UUID threadId) throws SQLException {
    try (PreparedStatement update =
        conn.prepareStatement("update harness_thread set name = ? where id = ?")) {
      update.setString(1, "renamed-" + threadId);
      update.setObject(2, threadId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private UUID insertEnvironment(Connection conn) throws SQLException {
    UUID environmentId = uuid();
    try (PreparedStatement environment =
        conn.prepareStatement(
            "insert into environment (id, name, registration_token) values (?, ?, ?)")) {
      environment.setObject(1, environmentId);
      environment.setString(2, "env-" + environmentId);
      environment.setString(3, "token-" + environmentId);
      assertEquals(1, environment.executeUpdate());
    }
    return environmentId;
  }

  private static void insertConnection(
      Connection conn, UUID environmentId, String status, String runtimeInfo) throws SQLException {
    try (PreparedStatement connection =
        conn.prepareStatement(
            "insert into environment_connection (environment_id, owner_node_id, lease_token,"
                + " status, runtime_info, last_seen_at, lease_until)"
                + " values (?, ?, ?, ?, cast(? as jsonb), current_timestamp, current_timestamp +"
                + " interval '60 seconds')")) {
      connection.setObject(1, environmentId);
      connection.setObject(2, uuid());
      connection.setObject(3, uuid());
      connection.setString(4, status);
      connection.setString(5, runtimeInfo);
      assertEquals(1, connection.executeUpdate());
    }
  }

  private static void renewConnectionLease(Connection conn, UUID environmentId)
      throws SQLException {
    try (PreparedStatement renew =
        conn.prepareStatement(
            "update environment_connection set last_seen_at = current_timestamp, lease_until ="
                + " current_timestamp + interval '60 seconds' where environment_id = ?")) {
      renew.setObject(1, environmentId);
      assertEquals(1, renew.executeUpdate());
    }
  }

  private static void markConnectionReady(Connection conn, UUID environmentId) throws SQLException {
    try (PreparedStatement ready =
        conn.prepareStatement(
            "update environment_connection set status = 'READY', runtime_info = '{}'::jsonb where"
                + " environment_id = ?")) {
      ready.setObject(1, environmentId);
      assertEquals(1, ready.executeUpdate());
    }
  }

  private static void deleteConnection(Connection conn, UUID environmentId) throws SQLException {
    try (PreparedStatement delete =
        conn.prepareStatement("delete from environment_connection where environment_id = ?")) {
      delete.setObject(1, environmentId);
      assertEquals(1, delete.executeUpdate());
    }
  }

  private static void renameEnvironment(Connection conn, UUID environmentId) throws SQLException {
    try (PreparedStatement rename =
        conn.prepareStatement(
            "update environment set name = ?, updated_at = current_timestamp where id = ?")) {
      rename.setString(1, "renamed-" + environmentId);
      rename.setObject(2, environmentId);
      assertEquals(1, rename.executeUpdate());
    }
  }

  private static void deleteEnvironment(Connection conn, UUID environmentId) throws SQLException {
    try (PreparedStatement delete = conn.prepareStatement("delete from environment where id = ?")) {
      delete.setObject(1, environmentId);
      assertEquals(1, delete.executeUpdate());
    }
  }

  // ---------------------------------------------------------------------------
  // Observer
  // ---------------------------------------------------------------------------

  /** 独立观察者连接：只在超时内等待确定数量的通知，因此“没有通知”是被观测的结果。 */
  private static final class ChannelListener implements AutoCloseable {

    private final String channel;
    private final Connection connection;
    private final PGConnection notifications;

    private ChannelListener(String channel) throws SQLException {
      this.channel = channel;
      this.connection = newConnection();
      this.connection.setAutoCommit(true);
      this.notifications = connection.unwrap(PGConnection.class);
      try (Statement listen = connection.createStatement()) {
        listen.execute("LISTEN " + channel);
      }
    }

    private List<String> await(int expectedCount) throws SQLException {
      List<String> payloads = new ArrayList<>();
      long deadlineNanos = System.nanoTime() + 5_000_000_000L;
      while (payloads.size() < expectedCount && System.nanoTime() < deadlineNanos) {
        collect(payloads);
      }
      assertTrue(
          payloads.size() >= expectedCount,
          () ->
              "timed out waiting for " + expectedCount + " notification(s); received=" + payloads);
      return payloads;
    }

    private void assertSilent() throws SQLException {
      List<String> payloads = new ArrayList<>();
      collect(payloads);
      assertEquals(
          List.of(), payloads, "uncommitted, rolled-back or unrelated writes must not notify");
    }

    private void collect(List<String> payloads) throws SQLException {
      PGNotification[] received = notifications.getNotifications(200);
      if (received == null) {
        return;
      }
      for (PGNotification notification : received) {
        assertEquals(channel, notification.getName());
        payloads.add(notification.getParameter());
      }
    }

    @Override
    public void close() throws SQLException {
      connection.close();
    }
  }
}
