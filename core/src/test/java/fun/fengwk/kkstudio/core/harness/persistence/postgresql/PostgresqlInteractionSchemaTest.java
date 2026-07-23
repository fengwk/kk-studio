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
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/** Verifies generic Interaction uniqueness and status-specific request/response facts. */
class PostgresqlInteractionSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
    }
  }

  @Test
  void ownerHasAtMostOneOpenInteraction() throws SQLException {
    long ownerId = FIXTURE_IDS.incrementAndGet();
    insertOpen(ownerId);

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_harness_interaction_open",
          () -> insertOpen(conn, FIXTURE_IDS.incrementAndGet(), ownerId));
    }

    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_interaction set status = 'RESOLVED', response = '{\"ok\":true}'"
                    + "::jsonb, resolved_at = current_timestamp, version = version + 1"
                    + " where owner_kind = 'THREAD' and owner_id = ? and status = 'OPEN'")) {
      ps.setLong(1, ownerId);
      assertEquals(1, ps.executeUpdate());
    }
    insertOpen(ownerId);

    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from harness_interaction")) {
      assertTrue(rs.next());
      assertEquals(2L, rs.getLong(1));
    }
  }

  @Test
  void responseAndResolutionTimeMatchInteractionStatus() throws SQLException {
    long ownerId = FIXTURE_IDS.incrementAndGet();

    assertInvalidInteraction(
        "ck_harness_interaction_open", ownerId, "OPEN", "{\"ok\":true}", null, null);
    assertInvalidInteraction(
        "ck_harness_interaction_resolved", ownerId, "RESOLVED", null, null, future());
    assertInvalidInteraction(
        "ck_harness_interaction_cancelled_or_expired", ownerId, "CANCELLED", null, null, null);
    assertInvalidInteraction(
        "ck_harness_interaction_cancelled_or_expired",
        ownerId,
        "CANCELLED",
        "{\"ignored\":true}",
        null,
        future());
    assertInvalidInteraction(
        "ck_harness_interaction_time_order", ownerId, "EXPIRED", null, null, future());
  }

  private void insertOpen(long ownerId) throws SQLException {
    try (Connection conn = newConnection()) {
      insertOpen(conn, FIXTURE_IDS.incrementAndGet(), ownerId);
    }
  }

  private void insertOpen(Connection conn, long interactionId, long ownerId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_interaction (id, owner_kind, owner_id, handler_type, request,"
                + " status, version) values (?, 'THREAD', ?, 'clarify', '{}'::jsonb, 'OPEN', 0)")) {
      ps.setLong(1, interactionId);
      ps.setLong(2, ownerId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void assertInvalidInteraction(
      String constraint,
      long ownerId,
      String status,
      String responseJson,
      Timestamp expiresAt,
      Timestamp resolvedAt)
      throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          constraint,
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_interaction (id, owner_kind, owner_id, handler_type,"
                        + " request, status, response, expires_at, version, resolved_at) values"
                        + " (?, 'THREAD', ?, 'clarify', '{}'::jsonb, ?, ?, ?, 0, ?)")) {
              ps.setLong(1, FIXTURE_IDS.incrementAndGet());
              ps.setLong(2, ownerId);
              ps.setString(3, status);
              if (responseJson == null) {
                ps.setNull(4, Types.OTHER);
              } else {
                ps.setObject(4, responseJson, Types.OTHER);
              }
              setTimestamp(ps, 5, expiresAt);
              setTimestamp(ps, 6, resolvedAt);
              ps.executeUpdate();
            }
          });
    }
  }

  private static Timestamp future() {
    return Timestamp.from(Instant.now().plusSeconds(60));
  }

  private static void setTimestamp(PreparedStatement ps, int index, Timestamp value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      ps.setTimestamp(index, value);
    }
  }
}
