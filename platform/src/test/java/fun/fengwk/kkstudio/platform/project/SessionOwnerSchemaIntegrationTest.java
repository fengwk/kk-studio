package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 验证 {@code session_owner} 排他弧的关系约束与主键并发互斥。 */
class SessionOwnerSchemaIntegrationTest extends PostgresSchemaSupport {

  private UUID sessionId;
  private UUID secondSessionId;
  private UUID chatId;
  private UUID canvasId;
  private UUID projectId;
  private UUID issueAgentSessionId;

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);

      String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
      createAgentDefinition(conn, agentName);

      sessionId = UUID.randomUUID();
      secondSessionId = UUID.randomUUID();
      chatId = UUID.randomUUID();
      canvasId = UUID.randomUUID();
      projectId = UUID.randomUUID();
      UUID issueId = UUID.randomUUID();
      issueAgentSessionId = UUID.randomUUID();
      UUID threadId = UUID.randomUUID();

      createHarnessSession(conn, sessionId);
      createHarnessSession(conn, secondSessionId);
      execute(
          conn,
          "insert into chat (id, title, agent_name) values (?, 'chat', ?)",
          chatId,
          agentName);
      execute(conn, "insert into canvas_document (id, title) values (?, 'canvas')", canvasId);
      execute(conn, "insert into project (id, title) values (?, 'project')", projectId);
      execute(
          conn,
          "insert into project_issue (id, project_id, number, title, status)"
              + " values (?, ?, 1, 'issue', 'TODO')",
          issueId,
          projectId);
      createHarnessThread(conn, threadId, sessionId);
      execute(
          conn,
          "insert into project_issue_agent_session"
              + " (id, issue_id, agent_name, session_id, thread_id)"
              + " values (?, ?, ?, ?, ?)",
          issueAgentSessionId,
          issueId,
          agentName,
          sessionId,
          threadId);
    }
  }

  @Test
  void exactlyOneOwnerAndPerOwnerCardinalityAreRelationalConstraints() throws SQLException {
    // 测试意图：单行恰有一个 owner；session 全局唯一；IssueAgentSession 至多一个长期 Session。
    try (Connection conn = newConnection()) {
      assertEquals(
          1,
          execute(
              conn,
              "insert into session_owner (session_id, chat_id) values (?, ?)",
              sessionId,
              chatId));

      assertConstraintRejected(
          conn,
          "pk_session_owner",
          () ->
              execute(
                  conn,
                  "insert into session_owner (session_id, canvas_id) values (?, ?)",
                  sessionId,
                  canvasId));

      assertConstraintRejected(
          conn,
          "ck_session_owner_exactly_one",
          () ->
              execute(conn, "insert into session_owner (session_id) values (?)", secondSessionId));

      assertEquals(1, execute(conn, "delete from session_owner where session_id = ?", sessionId));
      assertEquals(
          1,
          execute(
              conn,
              "insert into session_owner (session_id, issue_agent_session_id) values (?, ?)",
              sessionId,
              issueAgentSessionId));

      assertConstraintRejected(
          conn,
          "uk_session_owner_issue_agent_session",
          () ->
              execute(
                  conn,
                  "insert into session_owner (session_id, issue_agent_session_id) values (?, ?)",
                  secondSessionId,
                  issueAgentSessionId));

      assertConstraintRejected(
          conn,
          "ck_session_owner_exactly_one",
          () ->
              execute(
                  conn,
                  "update session_owner set canvas_id = ? where session_id = ?",
                  canvasId,
                  sessionId));
    }
  }

  @Test
  void concurrentOwnerClaimsHaveExactlyOneWinner() throws Exception {
    // 测试意图：不同 owner 同时认领同一 Session 时，由 session_id 主键线性化为一成一败。
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> chat = executor.submit(() -> claim(barrier, "chat_id", chatId, "CHAT"));
      Future<String> issueAgent =
          executor.submit(
              () ->
                  claim(
                      barrier,
                      "issue_agent_session_id",
                      issueAgentSessionId,
                      "ISSUE_AGENT_SESSION"));

      Set<String> outcomes =
          Set.of(chat.get(10, TimeUnit.SECONDS), issueAgent.get(10, TimeUnit.SECONDS));
      assertEquals(Set.of("SUCCESS", "CONFLICT"), outcomes);

      try (Connection conn = newConnection();
          PreparedStatement statement =
              conn.prepareStatement("select count(*) from session_owner where session_id = ?")) {
        statement.setObject(1, sessionId);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getInt(1));
        }
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private String claim(CyclicBarrier barrier, String ownerColumn, UUID ownerId, String label)
      throws Exception {
    try (Connection conn = newConnection()) {
      conn.setAutoCommit(false);
      barrier.await();
      try {
        execute(
            conn,
            "insert into session_owner (session_id, " + ownerColumn + ") values (?, ?)",
            sessionId,
            ownerId);
        conn.commit();
        return "SUCCESS";
      } catch (PSQLException error) {
        conn.rollback();
        if ("23505".equals(error.getSQLState())
            && error.getServerErrorMessage() != null
            && "pk_session_owner".equals(error.getServerErrorMessage().getConstraint())) {
          return "CONFLICT";
        }
        throw new AssertionError(label + " claim failed unexpectedly", error);
      }
    }
  }

  private static void createHarnessSession(Connection conn, UUID id) throws SQLException {
    execute(
        conn,
        "insert into harness_session (id, name, created_at)"
            + " values (?, 'session', clock_timestamp())",
        id);
  }

  private static void createHarnessThread(Connection conn, UUID threadId, UUID sessionId)
      throws SQLException {
    UUID rootEntryId = UUID.randomUUID();
    execute(
        conn,
        "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
            + " values (?, ?, 'ROOT', '{}'::jsonb, clock_timestamp())",
        rootEntryId,
        sessionId);
    execute(
        conn,
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
            + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, '"
            + "0".repeat(64)
            + "', 'test-thread', false, 1, 0, clock_timestamp(), clock_timestamp())",
        threadId,
        sessionId,
        rootEntryId);
  }

  private static void createAgentDefinition(Connection conn, String agentName) throws SQLException {
    String providerName = "provider-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "model-" + FIXTURE_IDS.incrementAndGet();
    execute(
        conn,
        "insert into agent_provider"
            + " (name, provider_type, config, connection_generation_id)"
            + " values (?, 'openai', '{}'::jsonb, ?)",
        providerName,
        UUID.randomUUID());
    execute(
        conn,
        "insert into agent_model (provider_name, name, model_id, config)"
            + " values (?, ?, 'model-id', '{}'::jsonb)",
        providerName,
        modelName);
    execute(
        conn,
        "insert into agent_definition (name, model_provider_name, model_name, config)"
            + " values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);
  }

  private static int execute(Connection conn, String sql, Object... arguments) throws SQLException {
    try (PreparedStatement statement = conn.prepareStatement(sql)) {
      for (int i = 0; i < arguments.length; i++) {
        statement.setObject(i + 1, arguments[i]);
      }
      return statement.executeUpdate();
    }
  }

  private static void assertConstraintRejected(
      Connection conn, String expectedConstraint, SqlAction action) throws SQLException {
    boolean autoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    SQLException failure = null;
    try {
      action.run();
      conn.commit();
    } catch (SQLException error) {
      failure = error;
      conn.rollback();
    } finally {
      conn.setAutoCommit(autoCommit);
    }
    assertTrue(failure instanceof PSQLException, "expected PostgreSQL constraint violation");
    var serverError = ((PSQLException) failure).getServerErrorMessage();
    assertNotNull(serverError);
    assertEquals(expectedConstraint, serverError.getConstraint());
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }
}
