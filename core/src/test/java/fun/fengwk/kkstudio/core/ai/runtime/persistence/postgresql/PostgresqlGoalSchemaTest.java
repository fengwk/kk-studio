package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Verifies the {@code agent_thread_goal} product table: it is a product capability (not runtime
 * execution truth), so it carries its own non-{@code harness_} prefix and constraint contract.
 */
class PostgresqlGoalSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void goalRowIsBoundToTheOwningThread() throws SQLException {
    ThreadFixture thread = createThread();
    long missingThreadId = FIXTURE_IDS.incrementAndGet() + 100_000_000L;

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_thread_goal_thread",
          () -> insertGoal(conn, missingThreadId, "Ship the slice", 100L, "active"));
    }
    try (Connection conn = newConnection()) {
      assertEquals(1, insertGoal(conn, thread.threadId, "Ship the slice", 100L, "active"));
    }
  }

  @Test
  void goalBudgetAndTerminalReasonMatchTheRuntimeContract() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      insertGoal(conn, thread.threadId, "Ship the slice", 100L, "active");
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_thread_goal_token_budget_pos",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update agent_thread_goal set token_budget = 0 where thread_id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_thread_goal_reason",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update agent_thread_goal set status = 'complete' where thread_id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update agent_thread_goal set status = 'complete', reason = 'verified'"
                    + " where thread_id = ?")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void goalObjectiveMustBeNonBlank() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection()) {
      insertGoal(conn, thread.threadId, "Ship the slice", null, "active");
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_thread_goal_objective",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update agent_thread_goal set objective = '   ' where thread_id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private static int insertGoal(
      Connection conn, long threadId, String objective, Long tokenBudget, String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_thread_goal (thread_id, objective, token_budget, status)"
                + " values (?, ?, ?, ?)")) {
      ps.setLong(1, threadId);
      ps.setString(2, objective);
      if (tokenBudget == null) {
        ps.setNull(3, Types.BIGINT);
      } else {
        ps.setLong(3, tokenBudget);
      }
      ps.setString(4, status);
      return ps.executeUpdate();
    }
  }
}
