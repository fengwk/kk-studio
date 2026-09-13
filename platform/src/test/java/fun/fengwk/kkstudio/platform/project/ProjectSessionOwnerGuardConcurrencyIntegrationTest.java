package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 验证 harness_session_owner_guard 在真实 PostgreSQL 下跨四大 Session 归属边 （chat_session, canvas_session,
 * project_session, issue_run_session）的串行互斥、 高并发竞态安全（恰一成功一失败）、删除释放后重绑定与 session_id 严禁 UPDATE 约束。
 */
class ProjectSessionOwnerGuardConcurrencyIntegrationTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void testFourRelationsSerialMutualExclusion() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      createAgentDefinition(conn, agentName);
      UUID session = UUID.randomUUID();
      createHarnessSession(conn, session);

      UUID chat = UUID.randomUUID();
      createChat(conn, chat, agentName);

      UUID canvas = UUID.randomUUID();
      createCanvas(conn, canvas);

      UUID project = UUID.randomUUID();
      createProject(conn, project, agentName);

      UUID issue = UUID.randomUUID();
      createIssue(conn, issue, project);

      UUID run = UUID.randomUUID();
      createIssueRun(conn, run, issue, agentName);

      // 1. 绑定 chat_session -> guard count = 1
      try (PreparedStatement ps =
          conn.prepareStatement("insert into chat_session (chat_id, session_id) values (?, ?)")) {
        ps.setObject(1, chat);
        ps.setObject(2, session);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(1, getGuardCount(conn, session));

      // 其余三大关系尝试绑定同一 session 均被 chk_harness_session_single_owner 拒绝
      assertConstraintViolation(
          conn,
          "chk_harness_session_single_owner",
          "insert into canvas_session (canvas_id, session_id) values (?, ?)",
          canvas,
          session);
      assertConstraintViolation(
          conn,
          "chk_harness_session_single_owner",
          "insert into project_session (project_id, session_id) values (?, ?)",
          project,
          session);
      assertConstraintViolation(
          conn,
          "chk_harness_session_single_owner",
          "insert into issue_run_session (run_id, session_id) values (?, ?)",
          run,
          session);

      // 2. 解绑 chat_session -> guard count = 0
      try (PreparedStatement ps =
          conn.prepareStatement("delete from chat_session where chat_id = ?")) {
        ps.setObject(1, chat);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(0, getGuardCount(conn, session));

      // 3. 绑定 canvas_session -> guard count = 1
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into canvas_session (canvas_id, session_id) values (?, ?)")) {
        ps.setObject(1, canvas);
        ps.setObject(2, session);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(1, getGuardCount(conn, session));

      assertConstraintViolation(
          conn,
          "chk_harness_session_single_owner",
          "insert into project_session (project_id, session_id) values (?, ?)",
          project,
          session);

      // 4. 解绑 canvas_session -> 绑定 project_session
      try (PreparedStatement ps =
          conn.prepareStatement("delete from canvas_session where canvas_id = ?")) {
        ps.setObject(1, canvas);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(0, getGuardCount(conn, session));

      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into project_session (project_id, session_id) values (?, ?)")) {
        ps.setObject(1, project);
        ps.setObject(2, session);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(1, getGuardCount(conn, session));

      assertConstraintViolation(
          conn,
          "chk_harness_session_single_owner",
          "insert into issue_run_session (run_id, session_id) values (?, ?)",
          run,
          session);

      // 5. 解绑 project_session -> 绑定 issue_run_session
      try (PreparedStatement ps =
          conn.prepareStatement("delete from project_session where project_id = ?")) {
        ps.setObject(1, project);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(0, getGuardCount(conn, session));

      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into issue_run_session (run_id, session_id) values (?, ?)")) {
        ps.setObject(1, run);
        ps.setObject(2, session);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(1, getGuardCount(conn, session));

      // 6. 解绑 issue_run_session -> guard count 回到 0
      try (PreparedStatement ps =
          conn.prepareStatement("delete from issue_run_session where run_id = ?")) {
        ps.setObject(1, run);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(0, getGuardCount(conn, session));
    }
  }

  @Test
  void testChatVsProjectConcurrency() throws Exception {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    UUID session = UUID.randomUUID();
    UUID chat = UUID.randomUUID();
    UUID project = UUID.randomUUID();

    try (Connection conn = newConnection()) {
      createAgentDefinition(conn, agentName);
      createHarnessSession(conn, session);
      createChat(conn, chat, agentName);
      createProject(conn, project, agentName);
    }

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<String> task1 =
          () -> {
            try (Connection c = newConnection()) {
              c.setAutoCommit(false);
              barrier.await();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "insert into chat_session (chat_id, session_id) values (?, ?)")) {
                ps.setObject(1, chat);
                ps.setObject(2, session);
                ps.executeUpdate();
              }
              c.commit();
              return "CHAT_SUCCESS";
            } catch (PSQLException e) {
              if (isSingleOwnerConflict(e)) {
                return "CHAT_CONFLICT";
              }
              throw e;
            }
          };

      Callable<String> task2 =
          () -> {
            try (Connection c = newConnection()) {
              c.setAutoCommit(false);
              barrier.await();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "insert into project_session (project_id, session_id) values (?, ?)")) {
                ps.setObject(1, project);
                ps.setObject(2, session);
                ps.executeUpdate();
              }
              c.commit();
              return "PROJECT_SUCCESS";
            } catch (PSQLException e) {
              if (isSingleOwnerConflict(e)) {
                return "PROJECT_CONFLICT";
              }
              throw e;
            }
          };

      Future<String> f1 = executor.submit(task1);
      Future<String> f2 = executor.submit(task2);

      String r1 = f1.get(10, TimeUnit.SECONDS);
      String r2 = f2.get(10, TimeUnit.SECONDS);

      boolean chatWon = "CHAT_SUCCESS".equals(r1) && "PROJECT_CONFLICT".equals(r2);
      boolean projectWon = "PROJECT_SUCCESS".equals(r2) && "CHAT_CONFLICT".equals(r1);
      assertTrue(chatWon || projectWon, "Exactly one must commit and the other fail with conflict");

      try (Connection conn = newConnection()) {
        assertEquals(1, getGuardCount(conn, session));

        if (chatWon) {
          try (PreparedStatement ps =
              conn.prepareStatement("delete from chat_session where chat_id = ?")) {
            ps.setObject(1, chat);
            assertEquals(1, ps.executeUpdate());
          }
          assertEquals(0, getGuardCount(conn, session));

          // 释放后 project_session 可以绑定
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "insert into project_session (project_id, session_id) values (?, ?)")) {
            ps.setObject(1, project);
            ps.setObject(2, session);
            assertEquals(1, ps.executeUpdate());
          }
        } else {
          try (PreparedStatement ps =
              conn.prepareStatement("delete from project_session where project_id = ?")) {
            ps.setObject(1, project);
            assertEquals(1, ps.executeUpdate());
          }
          assertEquals(0, getGuardCount(conn, session));

          // 释放后 chat_session 可以绑定
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "insert into chat_session (chat_id, session_id) values (?, ?)")) {
            ps.setObject(1, chat);
            ps.setObject(2, session);
            assertEquals(1, ps.executeUpdate());
          }
        }
        assertEquals(1, getGuardCount(conn, session));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testProjectVsRunConcurrency() throws Exception {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    UUID session = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID issue = UUID.randomUUID();
    UUID run = UUID.randomUUID();

    try (Connection conn = newConnection()) {
      createAgentDefinition(conn, agentName);
      createHarnessSession(conn, session);
      createProject(conn, project, agentName);
      createIssue(conn, issue, project);
      createIssueRun(conn, run, issue, agentName);
    }

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<String> task1 =
          () -> {
            try (Connection c = newConnection()) {
              c.setAutoCommit(false);
              barrier.await();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "insert into project_session (project_id, session_id) values (?, ?)")) {
                ps.setObject(1, project);
                ps.setObject(2, session);
                ps.executeUpdate();
              }
              c.commit();
              return "PROJECT_SUCCESS";
            } catch (PSQLException e) {
              if (isSingleOwnerConflict(e)) {
                return "PROJECT_CONFLICT";
              }
              throw e;
            }
          };

      Callable<String> task2 =
          () -> {
            try (Connection c = newConnection()) {
              c.setAutoCommit(false);
              barrier.await();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "insert into issue_run_session (run_id, session_id) values (?, ?)")) {
                ps.setObject(1, run);
                ps.setObject(2, session);
                ps.executeUpdate();
              }
              c.commit();
              return "RUN_SUCCESS";
            } catch (PSQLException e) {
              if (isSingleOwnerConflict(e)) {
                return "RUN_CONFLICT";
              }
              throw e;
            }
          };

      Future<String> f1 = executor.submit(task1);
      Future<String> f2 = executor.submit(task2);

      String r1 = f1.get(10, TimeUnit.SECONDS);
      String r2 = f2.get(10, TimeUnit.SECONDS);

      boolean projectWon = "PROJECT_SUCCESS".equals(r1) && "RUN_CONFLICT".equals(r2);
      boolean runWon = "RUN_SUCCESS".equals(r2) && "PROJECT_CONFLICT".equals(r1);
      assertTrue(projectWon || runWon, "Exactly one must commit and the other fail with conflict");

      try (Connection conn = newConnection()) {
        assertEquals(1, getGuardCount(conn, session));

        if (projectWon) {
          try (PreparedStatement ps =
              conn.prepareStatement("delete from project_session where project_id = ?")) {
            ps.setObject(1, project);
            assertEquals(1, ps.executeUpdate());
          }
          assertEquals(0, getGuardCount(conn, session));

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "insert into issue_run_session (run_id, session_id) values (?, ?)")) {
            ps.setObject(1, run);
            ps.setObject(2, session);
            assertEquals(1, ps.executeUpdate());
          }
        } else {
          try (PreparedStatement ps =
              conn.prepareStatement("delete from issue_run_session where run_id = ?")) {
            ps.setObject(1, run);
            assertEquals(1, ps.executeUpdate());
          }
          assertEquals(0, getGuardCount(conn, session));

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "insert into project_session (project_id, session_id) values (?, ?)")) {
            ps.setObject(1, project);
            ps.setObject(2, session);
            assertEquals(1, ps.executeUpdate());
          }
        }
        assertEquals(1, getGuardCount(conn, session));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testSessionIdUpdateRejectedOnAllFourRelations() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      createAgentDefinition(conn, agentName);
      UUID s1 = UUID.randomUUID();
      UUID s2 = UUID.randomUUID();
      createHarnessSession(conn, s1);
      createHarnessSession(conn, s2);

      UUID chat = UUID.randomUUID();
      createChat(conn, chat, agentName);

      UUID canvas = UUID.randomUUID();
      createCanvas(conn, canvas);

      UUID project = UUID.randomUUID();
      createProject(conn, project, agentName);

      UUID issue = UUID.randomUUID();
      createIssue(conn, issue, project);

      UUID run = UUID.randomUUID();
      createIssueRun(conn, run, issue, agentName);

      // 1. chat_session UPDATE 拒绝
      try (PreparedStatement ps =
          conn.prepareStatement("insert into chat_session (chat_id, session_id) values (?, ?)")) {
        ps.setObject(1, chat);
        ps.setObject(2, s1);
        assertEquals(1, ps.executeUpdate());
      }
      assertConstraintViolation(
          conn,
          "chk_harness_session_no_session_update",
          "update chat_session set session_id = ? where chat_id = ?",
          s2,
          chat);
      try (PreparedStatement ps =
          conn.prepareStatement("delete from chat_session where chat_id = ?")) {
        ps.setObject(1, chat);
        ps.executeUpdate();
      }

      // 2. canvas_session UPDATE 拒绝
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into canvas_session (canvas_id, session_id) values (?, ?)")) {
        ps.setObject(1, canvas);
        ps.setObject(2, s1);
        assertEquals(1, ps.executeUpdate());
      }
      assertConstraintViolation(
          conn,
          "chk_harness_session_no_session_update",
          "update canvas_session set session_id = ? where canvas_id = ?",
          s2,
          canvas);
      try (PreparedStatement ps =
          conn.prepareStatement("delete from canvas_session where canvas_id = ?")) {
        ps.setObject(1, canvas);
        ps.executeUpdate();
      }

      // 3. project_session UPDATE 拒绝
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into project_session (project_id, session_id) values (?, ?)")) {
        ps.setObject(1, project);
        ps.setObject(2, s1);
        assertEquals(1, ps.executeUpdate());
      }
      assertConstraintViolation(
          conn,
          "chk_harness_session_no_session_update",
          "update project_session set session_id = ? where project_id = ?",
          s2,
          project);
      try (PreparedStatement ps =
          conn.prepareStatement("delete from project_session where project_id = ?")) {
        ps.setObject(1, project);
        ps.executeUpdate();
      }

      // 4. issue_run_session UPDATE 拒绝
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into issue_run_session (run_id, session_id) values (?, ?)")) {
        ps.setObject(1, run);
        ps.setObject(2, s1);
        assertEquals(1, ps.executeUpdate());
      }
      assertConstraintViolation(
          conn,
          "chk_harness_session_no_session_update",
          "update issue_run_session set session_id = ? where run_id = ?",
          s2,
          run);
      try (PreparedStatement ps =
          conn.prepareStatement("delete from issue_run_session where run_id = ?")) {
        ps.setObject(1, run);
        ps.executeUpdate();
      }
    }
  }

  private boolean isSingleOwnerConflict(PSQLException e) {
    ServerErrorMessage msg = e.getServerErrorMessage();
    String constraint = msg != null ? msg.getConstraint() : null;
    return "chk_harness_session_single_owner".equals(constraint)
        || "pk_harness_session_owner_guard".equals(constraint);
  }

  private int getGuardCount(Connection conn, UUID sessionId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "select count(*) from harness_session_owner_guard where session_id = ?")) {
      ps.setObject(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getInt(1);
      }
    }
  }

  private void assertConstraintViolation(
      Connection conn, String expectedConstraint, String sql, Object p1, Object p2)
      throws SQLException {
    boolean prev = conn.getAutoCommit();
    conn.setAutoCommit(false);
    SQLException thrown = null;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, p1);
      ps.setObject(2, p2);
      ps.executeUpdate();
      conn.commit();
    } catch (SQLException e) {
      thrown = e;
      conn.rollback();
    } finally {
      conn.setAutoCommit(prev);
    }
    assertTrue(thrown instanceof PSQLException, "Expected PSQLException but got " + thrown);
    ServerErrorMessage serverError = ((PSQLException) thrown).getServerErrorMessage();
    assertNotNull(serverError);
    assertEquals(expectedConstraint, serverError.getConstraint());
  }

  private void createAgentDefinition(Connection conn, String name) throws SQLException {
    String provider = "p-" + FIXTURE_IDS.incrementAndGet();
    String model = "m-" + FIXTURE_IDS.incrementAndGet();
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_provider (name, provider_type, config, connection_generation_id) "
                + "values (?, 'openai', '{}'::jsonb, ?::uuid)")) {
      ps.setString(1, provider);
      ps.setObject(2, UUID.randomUUID());
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_model (provider_name, name, model_id, config) "
                + "values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, provider);
      ps.setString(2, model);
      ps.setString(3, "wire-" + FIXTURE_IDS.incrementAndGet());
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_definition (name, model_provider_name, model_name, config) "
                + "values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, name);
      ps.setString(2, provider);
      ps.setString(3, model);
      ps.executeUpdate();
    }
  }

  private void createHarnessSession(Connection conn, UUID sessionId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_session (id, name, created_at) values (?, 'session', clock_timestamp())")) {
      ps.setObject(1, sessionId);
      ps.executeUpdate();
    }
  }

  private void createChat(Connection conn, UUID chatId, String agentName) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into chat (id, title, agent_name) values (?, 'chat-title', ?)")) {
      ps.setObject(1, chatId);
      ps.setString(2, agentName);
      ps.executeUpdate();
    }
  }

  private void createCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_document (id, title) values (?, 'canvas-title')")) {
      ps.setObject(1, canvasId);
      ps.executeUpdate();
    }
  }

  private void createProject(Connection conn, UUID projectId, String coordinator)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project (id, title, description, coordinator_agent_name) values (?, 'title', 'desc', ?)")) {
      ps.setObject(1, projectId);
      ps.setString(2, coordinator);
      ps.executeUpdate();
    }
  }

  private void createIssue(Connection conn, UUID issueId, UUID projectId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into issue (id, project_id, number, title, status) values (?, ?, 1, 'title', 'TODO')")) {
      ps.setObject(1, issueId);
      ps.setObject(2, projectId);
      ps.executeUpdate();
    }
  }

  private void createIssueRun(Connection conn, UUID runId, UUID issueId, String agentName)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into issue_run (id, issue_id, ordinal, role, actor_type, agent_name, status) "
                + "values (?, ?, 1, 'EXECUTOR', 'AGENT', ?, 'RUNNING')")) {
      ps.setObject(1, runId);
      ps.setObject(2, issueId);
      ps.setString(3, agentName);
      ps.executeUpdate();
    }
  }
}
