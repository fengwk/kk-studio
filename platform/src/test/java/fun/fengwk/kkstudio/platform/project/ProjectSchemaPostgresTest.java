package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 验证 Project/Issue schema 的核心表、约束与外键围栏。 */
class ProjectSchemaPostgresTest extends PostgresSchemaSupport {

  @FunctionalInterface
  interface SqlAction {
    void run() throws SQLException;
  }

  private void assertConstraintViolation(
      Connection conn, String expectedConstraint, SqlAction action) throws SQLException {
    boolean previousAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    SQLException thrown = null;
    try {
      action.run();
      conn.commit();
    } catch (SQLException e) {
      thrown = e;
    } catch (RuntimeException | Error e) {
      conn.rollback();
      throw e;
    } finally {
      if (thrown != null) {
        conn.rollback();
      }
      conn.setAutoCommit(previousAutoCommit);
    }
    assertTrue(thrown instanceof PSQLException, "Expected PSQLException but got " + thrown);
    ServerErrorMessage serverError = ((PSQLException) thrown).getServerErrorMessage();
    assertTrue(serverError != null, "Missing PostgreSQL server error message");
    assertEquals(
        expectedConstraint,
        serverError.getConstraint(),
        () ->
            "Expected constraint "
                + expectedConstraint
                + " but violated "
                + serverError.getConstraint());
  }

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void allProjectIssueTablesAreCreated() throws SQLException {
    Set<String> expectedTables =
        Set.of(
            "project",
            "project_issue",
            "project_issue_dependency",
            "project_issue_agent_session",
            "project_issue_run",
            "project_issue_activity",
            "project_issue_work",
            "session_owner");

    Set<String> actualTables = new HashSet<>();
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "select table_name from information_schema.tables "
                    + "where table_schema = 'public' and table_type = 'BASE TABLE'")) {
      ResultSet rs = stmt.executeQuery();
      while (rs.next()) {
        actualTables.add(rs.getString(1));
      }
    }

    assertTrue(actualTables.containsAll(expectedTables), "all Project/Issue tables must exist");
  }

  @Test
  void projectConstraintsAndColumnsAreEnforced() throws SQLException {
    UUID projectId = UUID.randomUUID();

    // 1. project 表绝不包含 coordinator_agent_name 列
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "select column_name from information_schema.columns "
                    + "where table_schema = 'public' and table_name = 'project' and column_name = 'coordinator_agent_name'")) {
      ResultSet rs = stmt.executeQuery();
      assertTrue(!rs.next(), "project table must have no coordinator_agent_name column");
    }

    // 2. max_review_rejections < 1 违反 chk_project_max_review_rejections
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_max_review_rejections",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project (id, title, max_review_rejections) values (?, 'Project 1', 0)")) {
              stmt.setObject(1, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // 3. next_issue_number < 1 违反 chk_project_next_issue_number
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_next_issue_number",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project (id, title, next_issue_number) values (?, 'Project 1', 0)")) {
              stmt.setObject(1, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // 4. blank title 违反 chk_project_title_not_blank
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_title_not_blank",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement("insert into project (id, title) values (?, '   ')")) {
              stmt.setObject(1, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // 5. 插入合法 project 成功，默认 yolo_enabled 为 true，max_review_rejections 为 3
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project (id, title, description) values (?, 'Project 1', 'Desc')")) {
      stmt.setObject(1, projectId);
      assertEquals(1, stmt.executeUpdate());
    }

    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "select yolo_enabled, max_review_rejections, next_issue_number, version from project where id = ?")) {
      stmt.setObject(1, projectId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("yolo_enabled"), "default yolo_enabled must be true");
        assertEquals(
            3, rs.getInt("max_review_rejections"), "default max_review_rejections must be 3");
        assertEquals(1L, rs.getLong("next_issue_number"), "default next_issue_number must be 1");
        assertEquals(0L, rs.getLong("version"), "default version must be 0");
      }
    }
  }

  @Test
  void issueUniqueProjectNumberAndArchivedCheckAreEnforced() throws SQLException {
    UUID projectId = UUID.randomUUID();
    insertProject(projectId);

    UUID issueId1 = UUID.randomUUID();
    UUID issueId2 = UUID.randomUUID();

    // Insert issue number 1
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue (id, project_id, number, title, status) "
                    + "values (?, ?, 1, 'Issue 1', 'TODO')")) {
      stmt.setObject(1, issueId1);
      stmt.setObject(2, projectId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Duplicate (project_id, number) must violate uk_project_issue_project_number
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_project_issue_project_number",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue (id, project_id, number, title, status) "
                        + "values (?, ?, 1, 'Issue 2', 'TODO')")) {
              stmt.setObject(1, issueId2);
              stmt.setObject(2, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // Invalid status violates chk_project_issue_status
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_status",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue (id, project_id, number, title, status) "
                        + "values (?, ?, 2, 'Issue 2', 'UNKNOWN_STATUS')")) {
              stmt.setObject(1, issueId2);
              stmt.setObject(2, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // Cannot archive non-terminal issue (TODO is not DONE or CANCELED)
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_archived_status",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "update project_issue set archived_at = clock_timestamp() where id = ?")) {
              stmt.setObject(1, issueId1);
              stmt.executeUpdate();
            }
          });
    }

    // BLOCKED status is allowed, but cannot be archived
    try (Connection conn = newConnection()) {
      try (PreparedStatement stmt =
          conn.prepareStatement("update project_issue set status = 'BLOCKED' where id = ?")) {
        stmt.setObject(1, issueId1);
        assertEquals(1, stmt.executeUpdate());
      }
      assertConstraintViolation(
          conn,
          "chk_project_issue_archived_status",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "update project_issue set archived_at = clock_timestamp() where id = ?")) {
              stmt.setObject(1, issueId1);
              stmt.executeUpdate();
            }
          });
    }

    // Once status is DONE, archived_at is allowed
    try (Connection conn = newConnection()) {
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "update project_issue set status = 'DONE', archived_at = clock_timestamp() where id = ?")) {
        stmt.setObject(1, issueId1);
        assertEquals(1, stmt.executeUpdate());
      }
    }
  }

  @Test
  void dependencyEnforcesSameProjectAndNoSelf() throws SQLException {
    UUID proj1 = UUID.randomUUID();
    UUID proj2 = UUID.randomUUID();
    insertProject(proj1);
    insertProject(proj2);

    UUID issueA = UUID.randomUUID();
    UUID issueB = UUID.randomUUID();
    UUID issueOtherProject = UUID.randomUUID();
    insertIssue(issueA, proj1, 1, "TODO");
    insertIssue(issueB, proj1, 2, "TODO");
    insertIssue(issueOtherProject, proj2, 1, "TODO");

    // Self dependency violates chk_project_issue_dependency_no_self
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_dependency_no_self",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_dependency (issue_id, depends_on_issue_id, project_id) "
                        + "values (?, ?, ?)")) {
              stmt.setObject(1, issueA);
              stmt.setObject(2, issueA);
              stmt.setObject(3, proj1);
              stmt.executeUpdate();
            }
          });
    }

    // Cross-project dependency violates fk_project_issue_dependency_depends_on
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_project_issue_dependency_depends_on",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_dependency (issue_id, depends_on_issue_id, project_id) "
                        + "values (?, ?, ?)")) {
              stmt.setObject(1, issueA);
              stmt.setObject(2, issueOtherProject);
              stmt.setObject(3, proj1);
              stmt.executeUpdate();
            }
          });
    }

    // Same project dependency succeeds
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_dependency (issue_id, depends_on_issue_id, project_id) "
                    + "values (?, ?, ?)")) {
      stmt.setObject(1, issueA);
      stmt.setObject(2, issueB);
      stmt.setObject(3, proj1);
      assertEquals(1, stmt.executeUpdate());
    }
  }

  @Test
  void projectIssueAgentSessionConstraintsAreEnforced() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "IN_PROGRESS");

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      createHarnessSession(conn, sessionId);
      createHarnessThread(conn, threadId, sessionId);
    }

    UUID agentSessionId = UUID.randomUUID();
    // 插入合法 project_issue_agent_session
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_agent_session (id, issue_id, agent_name, session_id, thread_id) "
                    + "values (?, ?, ?, ?, ?)")) {
      stmt.setObject(1, agentSessionId);
      stmt.setObject(2, issueId);
      stmt.setString(3, agentName);
      stmt.setObject(4, sessionId);
      stmt.setObject(5, threadId);
      assertEquals(1, stmt.executeUpdate());
    }

    // 重复 (issue_id, agent_name) 违背 uk_project_issue_agent_session_issue_agent
    UUID secondSessionId = UUID.randomUUID();
    UUID secondThreadId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      createHarnessSession(conn, secondSessionId);
      createHarnessThread(conn, secondThreadId, secondSessionId);
      assertConstraintViolation(
          conn,
          "uk_project_issue_agent_session_issue_agent",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_agent_session (id, issue_id, agent_name, session_id, thread_id) "
                        + "values (?, ?, ?, ?, ?)")) {
              stmt.setObject(1, UUID.randomUUID());
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.setObject(4, secondSessionId);
              stmt.setObject(5, secondThreadId);
              stmt.executeUpdate();
            }
          });
    }

    // 重复 session_id 违背 uk_project_issue_agent_session_session
    String otherAgent = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(otherAgent);
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_project_issue_agent_session_session",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_agent_session (id, issue_id, agent_name, session_id, thread_id) "
                        + "values (?, ?, ?, ?, ?)")) {
              stmt.setObject(1, UUID.randomUUID());
              stmt.setObject(2, issueId);
              stmt.setString(3, otherAgent);
              stmt.setObject(4, sessionId);
              stmt.setObject(5, secondThreadId);
              stmt.executeUpdate();
            }
          });
    }

    // 重复 thread_id 违背 uk_project_issue_agent_session_thread
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_project_issue_agent_session_thread",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_agent_session (id, issue_id, agent_name, session_id, thread_id) "
                        + "values (?, ?, ?, ?, ?)")) {
              stmt.setObject(1, UUID.randomUUID());
              stmt.setObject(2, issueId);
              stmt.setString(3, otherAgent);
              stmt.setObject(4, secondSessionId);
              stmt.setObject(5, threadId);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void issueRunEnforcesRoleActorAndSingleActiveConstraints() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "IN_PROGRESS");

    UUID execRunId = UUID.randomUUID();
    // Valid executor run
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status) "
                    + "values (?, ?, 1, 'EXECUTOR', ?, 'RUNNING')")) {
      stmt.setObject(1, execRunId);
      stmt.setObject(2, issueId);
      stmt.setString(3, agentName);
      assertEquals(1, stmt.executeUpdate());
    }

    // Cannot insert a second active run on the same issue -> violates
    // uk_project_issue_run_single_active
    UUID secondRunId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_project_issue_run_single_active",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, waiting_reason) "
                        + "values (?, ?, 2, 'EXECUTOR', ?, 'WAITING_HUMAN', 'need input')")) {
              stmt.setObject(1, secondRunId);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // Executor with submission_run_id -> violates chk_project_issue_run_role
    UUID invalidExecId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_role",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, submission_run_id, status, completed_at, terminal_action_id, outcome, result) "
                        + "values (?, ?, 3, 'EXECUTOR', ?, ?, 'COMPLETED', clock_timestamp(), 'act-3', 'SUBMITTED', '{\"summary\":\"s\"}'::jsonb)")) {
              stmt.setObject(1, invalidExecId);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.setObject(4, execRunId);
              stmt.executeUpdate();
            }
          });
    }

    // Reviewer requires submission_run_id -> violates chk_project_issue_run_role if missing
    UUID invalidRevId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_role",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, completed_at, terminal_action_id, outcome, result) "
                        + "values (?, ?, 4, 'REVIEWER', ?, 'COMPLETED', clock_timestamp(), 'act-4', 'APPROVED', '{\"summary\":\"s\"}'::jsonb)")) {
              stmt.setObject(1, invalidRevId);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // Reviewer with submission_run_id from DIFFERENT issue -> violates
    // fk_project_issue_run_submission
    UUID otherIssueId = UUID.randomUUID();
    insertIssue(otherIssueId, proj, 2, "IN_PROGRESS");
    UUID foreignRevId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_project_issue_run_submission",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, submission_run_id, status, outcome, completed_at, terminal_action_id, result) "
                        + "values (?, ?, 1, 'REVIEWER', ?, ?, 'COMPLETED', 'APPROVED', clock_timestamp(), 'act-rev-diff', '{\"summary\":\"approved\"}'::jsonb)")) {
              stmt.setObject(1, foreignRevId);
              stmt.setObject(2, otherIssueId);
              stmt.setString(3, agentName);
              stmt.setObject(4, execRunId);
              stmt.executeUpdate();
            }
          });
    }

    // Complete first executor run
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "update project_issue_run set status = 'COMPLETED', outcome = 'SUBMITTED', completed_at = clock_timestamp(), terminal_action_id = 'act-exec-1', result = '{\"summary\":\"done\"}'::jsonb where id = ?")) {
      stmt.setObject(1, execRunId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Reviewer with valid submission_run_id on same issue succeeds
    UUID revRunId = UUID.randomUUID();
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, submission_run_id, status, outcome, completed_at, terminal_action_id, result) "
                    + "values (?, ?, 2, 'REVIEWER', ?, ?, 'COMPLETED', 'APPROVED', clock_timestamp(), 'act-rev-1', '{\"summary\":\"approved\"}'::jsonb)")) {
      stmt.setObject(1, revRunId);
      stmt.setObject(2, issueId);
      stmt.setString(3, agentName);
      stmt.setObject(4, execRunId);
      assertEquals(1, stmt.executeUpdate());
    }
  }

  @Test
  void issueActivityConstraintsAreEnforced() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "TODO");

    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_activity (issue_id, sequence, kind, actor_type, body, idempotency_key) "
                    + "values (?, 1, 'HUMAN_INPUT', 'HUMAN', 'Hello', 'key-123')")) {
      stmt.setObject(1, issueId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Duplicate idempotency_key on same issue violates uk_project_issue_activity_idempotency
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_project_issue_activity_idempotency",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_activity (issue_id, sequence, kind, actor_type, body, idempotency_key) "
                        + "values (?, 2, 'HUMAN_INPUT', 'HUMAN', 'Hello again', 'key-123')")) {
              stmt.setObject(1, issueId);
              stmt.executeUpdate();
            }
          });
    }

    // AGENT actor_type requires non-null actor_agent_name
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_activity_actor",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_activity (issue_id, sequence, kind, actor_type, body) "
                        + "values (?, 3, 'INSTRUCTION', 'AGENT', 'do this')")) {
              stmt.setObject(1, issueId);
              stmt.executeUpdate();
            }
          });
    }

    // HUMAN actor_type with non-null actor_agent_name violates chk_project_issue_activity_actor
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_activity_actor",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_activity (issue_id, sequence, kind, actor_type, actor_agent_name, body) "
                        + "values (?, 4, 'HUMAN_INPUT', 'HUMAN', ?, 'hello')")) {
              stmt.setObject(1, issueId);
              stmt.setString(2, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // REVIEW_DECISION requires non-null decision and non-null submission_run_id
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_activity_decision_shape",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_activity (issue_id, sequence, kind, actor_type, body) "
                        + "values (?, 5, 'REVIEW_DECISION', 'HUMAN', 'review done')")) {
              stmt.setObject(1, issueId);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void issueWorkTableOperatesCorrectly() throws SQLException {
    UUID proj = UUID.randomUUID();
    insertProject(proj);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "TODO");

    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue_work (issue_id, wake_version, due_at) "
                    + "values (?, 1, clock_timestamp())")) {
      stmt.setObject(1, issueId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Foreign key to project_issue is enforced
    UUID nonExistentIssueId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_project_issue_work_issue",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_work (issue_id, wake_version) values (?, 1)")) {
              stmt.setObject(1, nonExistentIssueId);
              stmt.executeUpdate();
            }
          });
    }

    // wake_version <= 0 违背 chk_project_issue_work_wake
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_work_wake",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "update project_issue_work set wake_version = 0 where issue_id = ?")) {
              stmt.setObject(1, issueId);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void testProjectAndIssueDescriptionLengthConstraints() throws SQLException {
    UUID proj = UUID.randomUUID();

    // 超过 65536 字节的 project description 被拒绝
    String oversizedDesc = "a".repeat(65537);
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_description_len",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project (id, title, description) values (?, 'Proj', ?)")) {
              stmt.setObject(1, proj);
              stmt.setString(2, oversizedDesc);
              stmt.executeUpdate();
            }
          });
    }

    // 恰好 65536 字节成功
    String exactDesc = "a".repeat(65536);
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project (id, title, description) values (?, 'Proj', ?)")) {
      stmt.setObject(1, proj);
      stmt.setString(2, exactDesc);
      assertEquals(1, stmt.executeUpdate());
    }

    // 超过 65536 字节的 issue description 被拒绝
    UUID issueId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_description_len",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue (id, project_id, number, title, description, status) "
                        + "values (?, ?, 1, 'Issue', ?, 'TODO')")) {
              stmt.setObject(1, issueId);
              stmt.setObject(2, proj);
              stmt.setString(3, oversizedDesc);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void testIssueRunLifecycleConstraints() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "IN_PROGRESS");

    // RUNNING 状态必须 waiting_reason is null
    UUID run1 = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_lifecycle",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, waiting_reason) "
                        + "values (?, ?, 1, 'EXECUTOR', ?, 'RUNNING', 'some reason')")) {
              stmt.setObject(1, run1);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // WAITING_HUMAN 状态必须有 non-blank waiting_reason
    UUID run2 = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_lifecycle",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, waiting_reason) "
                        + "values (?, ?, 2, 'EXECUTOR', ?, 'WAITING_HUMAN', '   ')")) {
              stmt.setObject(1, run2);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // COMPLETED 状态必须 result is not null 且 waiting_reason is null
    UUID run3 = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_lifecycle",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, result) "
                        + "values (?, ?, 3, 'EXECUTOR', ?, 'COMPLETED', null)")) {
              stmt.setObject(1, run3);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // FAILED 状态必须有 non-blank waiting_reason
    UUID run4 = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_lifecycle",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, waiting_reason, completed_at) "
                        + "values (?, ?, 4, 'EXECUTOR', ?, 'FAILED', null, clock_timestamp())")) {
              stmt.setObject(1, run4);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // FAILED 状态仅 waiting_reason > 16384 bytes，稳定违背 chk_project_issue_run_waiting_reason_len
    String oversizedReason = "f".repeat(16385);
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_project_issue_run_waiting_reason_len",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project_issue_run (id, issue_id, ordinal, role, agent_name, status, waiting_reason, completed_at) "
                        + "values (?, ?, 5, 'EXECUTOR', ?, 'FAILED', ?, clock_timestamp())")) {
              stmt.setObject(1, UUID.randomUUID());
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.setString(4, oversizedReason);
              stmt.executeUpdate();
            }
          });
    }
  }

  private void insertAgentDefinition(String name) throws SQLException {
    try (Connection conn = newConnection()) {
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
  }

  private void insertProject(UUID id) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project (id, title, description) values (?, 'Project', 'Desc')")) {
      stmt.setObject(1, id);
      stmt.executeUpdate();
    }
  }

  private void insertIssue(UUID id, UUID projectId, long number, String status)
      throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project_issue (id, project_id, number, title, status) "
                    + "values (?, ?, ?, 'Issue', ?)")) {
      stmt.setObject(1, id);
      stmt.setObject(2, projectId);
      stmt.setLong(3, number);
      stmt.setString(4, status);
      stmt.executeUpdate();
    }
  }

  private static void createHarnessSession(Connection conn, UUID id) throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "insert into harness_session (id, name, created_at) values (?, 'session', clock_timestamp())")) {
      stmt.setObject(1, id);
      stmt.executeUpdate();
    }
  }

  private static void createHarnessThread(Connection conn, UUID threadId, UUID sessionId)
      throws SQLException {
    UUID rootEntryId = UUID.randomUUID();
    try (PreparedStatement entry =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
                    + " values (?, ?, 'ROOT', '{}'::jsonb, clock_timestamp())");
        PreparedStatement thread =
            conn.prepareStatement(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
                    + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '"
                    + "0".repeat(64)
                    + "', 'test-thread', false, 1, 0, clock_timestamp(), clock_timestamp())")) {
      entry.setObject(1, rootEntryId);
      entry.setObject(2, sessionId);
      entry.executeUpdate();

      thread.setObject(1, threadId);
      thread.setObject(2, sessionId);
      thread.setObject(3, rootEntryId);
      thread.executeUpdate();
    }
  }
}
