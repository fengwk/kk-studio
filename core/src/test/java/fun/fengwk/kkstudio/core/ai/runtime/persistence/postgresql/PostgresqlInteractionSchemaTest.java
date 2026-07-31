package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Verifies the exact Tool permission Interaction OPEN uniqueness and lifecycle constraints. */
class PostgresqlInteractionSchemaTest extends PostgresSchemaSupport {
  @BeforeEach
  void setup() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      applyBaseline(connection);
    }
  }

  @Test
  void toolInvocationHasAtMostOneOpenInteraction() throws SQLException {
    long toolInvocationId = FIXTURE_IDS.incrementAndGet();
    insert(toolInvocationId, "OPEN", null, null);
    try (Connection connection = newConnection()) {
      assertTransactionConstraintViolation(
          connection,
          "uk_harness_interaction_open",
          () -> insert(connection, toolInvocationId, "OPEN", null, null));
    }
    try (Connection connection = newConnection()) {
      insert(connection, toolInvocationId, "RESOLVED", "{\"approved\":true}", "current_timestamp");
    }
  }

  @Test
  void statusPayloadConstraintsRejectInvalidOpenAndResolvedFacts() throws SQLException {
    long toolInvocationId = FIXTURE_IDS.incrementAndGet();
    try (Connection connection = newConnection()) {
      assertTransactionConstraintViolation(
          connection,
          "ck_harness_interaction_open",
          () -> insert(connection, toolInvocationId, "OPEN", "{\"approved\":true}", null));
      assertTransactionConstraintViolation(
          connection,
          "ck_harness_interaction_resolved",
          () -> insert(connection, toolInvocationId, "RESOLVED", null, null));
      assertTransactionConstraintViolation(
          connection,
          "ck_harness_interaction_status",
          () -> insert(connection, toolInvocationId, "CANCELLED", null, null));
    }
  }

  private void insert(long toolInvocationId, String status, String response, String resolvedAt)
      throws SQLException {
    try (Connection connection = newConnection()) {
      insert(connection, toolInvocationId, status, response, resolvedAt);
    }
  }

  private void insert(
      Connection connection,
      long toolInvocationId,
      String status,
      String response,
      String resolvedAt)
      throws SQLException {
    String resolvedExpression = resolvedAt == null ? "null" : resolvedAt;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into harness_interaction (id, tool_invocation_id, request, status, response,"
                + " version, resolved_at) values (?, ?, '{}'::jsonb, ?, cast(? as jsonb), 0, "
                + resolvedExpression
                + ")")) {
      statement.setLong(1, FIXTURE_IDS.incrementAndGet());
      statement.setLong(2, toolInvocationId);
      statement.setString(3, status);
      statement.setString(4, response);
      assertEquals(1, statement.executeUpdate());
    }
  }
}
