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

/** 验证 V7 Project/Issue schema 在真实 PostgreSQL 上的迁移、8 张核心表、约束与外键围栏。 */
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
  void allEightProjectIssueTablesAreCreated() throws SQLException {
    Set<String> expectedTables =
        Set.of(
            "project",
            "project_session",
            "issue",
            "issue_dependency",
            "issue_input",
            "issue_run",
            "issue_run_session",
            "issue_controller_work");

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

    assertTrue(
        actualTables.containsAll(expectedTables), "All 8 V7 tables must exist in public schema");
  }

  @Test
  void projectCoordinatorForeignKeyIsEnforced() throws SQLException {
    UUID projectId = UUID.randomUUID();
    // Inserting project referencing non-existent agent_definition must fail with
    // fk_project_coordinator
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_project_coordinator",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into project (id, title, description, coordinator_agent_name) "
                        + "values (?, 'Project 1', 'Desc', 'non-existent-agent')")) {
              stmt.setObject(1, projectId);
              stmt.executeUpdate();
            }
          });
    }

    // Insert valid agent and then project succeeds
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project (id, title, description, coordinator_agent_name) "
                    + "values (?, 'Project 1', 'Desc', ?)")) {
      stmt.setObject(1, projectId);
      stmt.setString(2, agentName);
      assertEquals(1, stmt.executeUpdate());
    }

    // Deleting agent_definition must be restricted by fk_project_coordinator
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_project_coordinator",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement("delete from agent_definition where name = ?")) {
              stmt.setString(1, agentName);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void issueUniqueProjectNumberAndArchivedCheckAreEnforced() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID projectId = UUID.randomUUID();
    insertProject(projectId, agentName);

    UUID issueId1 = UUID.randomUUID();
    UUID issueId2 = UUID.randomUUID();

    // Insert issue number 1
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue (id, project_id, number, title, status) "
                    + "values (?, ?, 1, 'Issue 1', 'TODO')")) {
      stmt.setObject(1, issueId1);
      stmt.setObject(2, projectId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Duplicate (project_id, number) must violate uk_issue_project_number
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_issue_project_number",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue (id, project_id, number, title, status) "
                        + "values (?, ?, 1, 'Issue 2', 'TODO')")) {
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
          "chk_issue_archived_status",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "update issue set archived_at = clock_timestamp() where id = ?")) {
              stmt.setObject(1, issueId1);
              stmt.executeUpdate();
            }
          });
    }

    // Once status is DONE, archived_at is allowed
    try (Connection conn = newConnection()) {
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "update issue set status = 'DONE', archived_at = clock_timestamp() where id = ?")) {
        stmt.setObject(1, issueId1);
        assertEquals(1, stmt.executeUpdate());
      }
    }
  }

  @Test
  void dependencyEnforcesSameProjectAndNoSelf() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj1 = UUID.randomUUID();
    UUID proj2 = UUID.randomUUID();
    insertProject(proj1, agentName);
    insertProject(proj2, agentName);

    UUID issueA = UUID.randomUUID();
    UUID issueB = UUID.randomUUID();
    UUID issueOtherProject = UUID.randomUUID();
    insertIssue(issueA, proj1, 1, "TODO");
    insertIssue(issueB, proj1, 2, "TODO");
    insertIssue(issueOtherProject, proj2, 1, "TODO");

    // Self dependency violates chk_issue_dependency_no_self
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_issue_dependency_no_self",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_dependency (issue_id, depends_on_issue_id, project_id) "
                        + "values (?, ?, ?)")) {
              stmt.setObject(1, issueA);
              stmt.setObject(2, issueA);
              stmt.setObject(3, proj1);
              stmt.executeUpdate();
            }
          });
    }

    // Cross-project dependency violates fk_issue_dependency_depends_on
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_issue_dependency_depends_on",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_dependency (issue_id, depends_on_issue_id, project_id) "
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
                "insert into issue_dependency (issue_id, depends_on_issue_id, project_id) "
                    + "values (?, ?, ?)")) {
      stmt.setObject(1, issueA);
      stmt.setObject(2, issueB);
      stmt.setObject(3, proj1);
      assertEquals(1, stmt.executeUpdate());
    }
  }

  @Test
  void issueRunEnforcesRoleActorAndSingleActiveConstraints() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj, agentName);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "IN_PROGRESS");

    UUID execRunId = UUID.randomUUID();
    // Valid executor run
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue_run (id, issue_id, ordinal, role, actor_type, agent_name, status) "
                    + "values (?, ?, 1, 'EXECUTOR', 'AGENT', ?, 'RUNNING')")) {
      stmt.setObject(1, execRunId);
      stmt.setObject(2, issueId);
      stmt.setString(3, agentName);
      assertEquals(1, stmt.executeUpdate());
    }

    // Cannot insert a second active run on the same issue -> violates uk_issue_run_single_active
    UUID secondRunId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_issue_run_single_active",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_run (id, issue_id, ordinal, role, actor_type, agent_name, status) "
                        + "values (?, ?, 2, 'EXECUTOR', 'AGENT', ?, 'WAITING_HUMAN')")) {
              stmt.setObject(1, secondRunId);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // Executor cannot be HUMAN -> violates chk_issue_run_role
    UUID invalidExecId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_issue_run_role",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_run (id, issue_id, ordinal, role, actor_type, status) "
                        + "values (?, ?, 3, 'EXECUTOR', 'HUMAN', 'COMPLETED')")) {
              stmt.setObject(1, invalidExecId);
              stmt.setObject(2, issueId);
              stmt.executeUpdate();
            }
          });
    }

    // Reviewer requires submission_run_id -> violates chk_issue_run_role if missing
    UUID invalidRevId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "chk_issue_run_role",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_run (id, issue_id, ordinal, role, actor_type, agent_name, status) "
                        + "values (?, ?, 4, 'REVIEWER', 'AGENT', ?, 'COMPLETED')")) {
              stmt.setObject(1, invalidRevId);
              stmt.setObject(2, issueId);
              stmt.setString(3, agentName);
              stmt.executeUpdate();
            }
          });
    }

    // Reviewer with submission_run_id referencing same issue succeeds
    // (Mark first run COMPLETED so we can add another run or complete reviewer)
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "update issue_run set status = 'COMPLETED', outcome = 'SUBMITTED' where id = ?")) {
      stmt.setObject(1, execRunId);
      assertEquals(1, stmt.executeUpdate());
    }

    UUID revRunId = UUID.randomUUID();
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue_run (id, issue_id, ordinal, role, actor_type, submission_run_id, status, outcome) "
                    + "values (?, ?, 2, 'REVIEWER', 'HUMAN', ?, 'COMPLETED', 'APPROVED')")) {
      stmt.setObject(1, revRunId);
      stmt.setObject(2, issueId);
      stmt.setObject(3, execRunId);
      assertEquals(1, stmt.executeUpdate());
    }
  }

  @Test
  void issueInputIdempotencyIsEnforced() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj, agentName);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "TODO");

    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue_input (issue_id, sequence, kind, body, idempotency_key) "
                    + "values (?, 1, 'HUMAN', 'Hello', 'key-123')")) {
      stmt.setObject(1, issueId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Duplicate idempotency_key on same issue violates uk_issue_input_idempotency
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_issue_input_idempotency",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_input (issue_id, sequence, kind, body, idempotency_key) "
                        + "values (?, 2, 'HUMAN', 'Hello again', 'key-123')")) {
              stmt.setObject(1, issueId);
              stmt.executeUpdate();
            }
          });
    }
  }

  @Test
  void issueControllerWorkTableOperatesCorrectly() throws SQLException {
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();
    insertAgentDefinition(agentName);
    UUID proj = UUID.randomUUID();
    insertProject(proj, agentName);
    UUID issueId = UUID.randomUUID();
    insertIssue(issueId, proj, 1, "TODO");

    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue_controller_work (issue_id, wake_version, due_at) "
                    + "values (?, 1, clock_timestamp())")) {
      stmt.setObject(1, issueId);
      assertEquals(1, stmt.executeUpdate());
    }

    // Foreign key to issue is enforced
    UUID nonExistentIssueId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "fk_issue_controller_work_issue",
          () -> {
            try (PreparedStatement stmt =
                conn.prepareStatement(
                    "insert into issue_controller_work (issue_id, wake_version) values (?, 1)")) {
              stmt.setObject(1, nonExistentIssueId);
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

  private void insertProject(UUID id, String coordinator) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into project (id, title, description, coordinator_agent_name) "
                    + "values (?, 'Project', 'Desc', ?)")) {
      stmt.setObject(1, id);
      stmt.setString(2, coordinator);
      stmt.executeUpdate();
    }
  }

  private void insertIssue(UUID id, UUID projectId, long number, String status)
      throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "insert into issue (id, project_id, number, title, status) "
                    + "values (?, ?, ?, 'Issue', ?)")) {
      stmt.setObject(1, id);
      stmt.setObject(2, projectId);
      stmt.setLong(3, number);
      stmt.setString(4, status);
      stmt.executeUpdate();
    }
  }
}
