package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Verifies that non-Harness business data is fully represented by the PostgreSQL schema. */
class PostgresqlBusinessSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void agentResourcesKeepProviderAndModelOwnership() throws SQLException {
    String providerName = "provider-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "model-" + FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertProvider(conn, providerName);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_agent_model_provider", () -> insertModel(conn, "missing-provider", modelName));
    }
    try (Connection conn = newConnection()) {
      insertModel(conn, providerName, modelName);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_definition_model",
          () -> insertDefinition(conn, "missing-agent", "missing-provider", "missing-model"));
    }
  }

  @Test
  void chatEnvironmentNameRequiresCanonicalValueAndAgentNameIsRequired() throws SQLException {
    try (Connection conn = newConnection()) {
      insertChat(conn, FIXTURE_IDS.incrementAndGet(), null);
      insertChat(conn, FIXTURE_IDS.incrementAndGet(), "prod");
    }

    for (String invalid : new String[] {"", "   ", "\t", "\n", "\tprod\t"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "ck_chat_environment_name",
            () -> insertChat(conn, FIXTURE_IDS.incrementAndGet(), invalid));
      }
    }
    try (Connection conn = newConnection()) {
      SQLException tooLong =
          assertThrows(
              SQLException.class,
              () -> insertChat(conn, FIXTURE_IDS.incrementAndGet(), "x".repeat(129)));
      assertEquals("22001", tooLong.getSQLState());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into chat (id, title, agent_name, environment_name)"
                    + " values (?, 'schema-test', ?, null)")) {
      ps.setLong(1, FIXTURE_IDS.incrementAndGet());
      ps.setString(2, " ");
      assertTransactionConstraintViolation(conn, "ck_chat_agent_name", () -> ps.executeUpdate());
    }
  }

  @Test
  void canvasLinkStaysWithinItsOwningCanvas() throws SQLException {
    long firstCanvasId = FIXTURE_IDS.incrementAndGet();
    long secondCanvasId = FIXTURE_IDS.incrementAndGet();
    long firstNodeId = FIXTURE_IDS.incrementAndGet();
    long secondNodeId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, firstCanvasId);
      insertCanvas(conn, secondCanvasId);
      insertNode(conn, firstNodeId, firstCanvasId);
      insertNode(conn, secondNodeId, secondCanvasId);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_link_target",
          () -> insertLink(conn, firstCanvasId, firstNodeId, secondNodeId));
    }
  }

  @Test
  void canvasSchemaConstraintsRejectBadInputs() throws SQLException {
    long canvasId = FIXTURE_IDS.incrementAndGet();
    long nodeId = FIXTURE_IDS.incrementAndGet();
    long linkId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, nodeId, canvasId);
    }

    // Document: blank title is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_document_title_nonblank", () -> insertCanvasRow(conn, canvasId, " "));
    }

    // Node: blank name is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_name_nonblank",
          () -> insertNodeRow(conn, nodeId, canvasId, "RESOURCE", "", 100, 100));
    }

    // Node: zero width is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_width_pos",
          () -> insertNodeRow(conn, nodeId, canvasId, "RESOURCE", "n", 0, 100));
    }

    // Node: zero height is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_height_pos",
          () -> insertNodeRow(conn, nodeId, canvasId, "RESOURCE", "n", 100, 0));
    }

    // Link: self-loop is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_link_distinct", () -> insertLink(conn, canvasId, nodeId, nodeId));
    }

    // Dedup: blank command_id is rejected.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_command_id_nonblank",
          () -> insertDedup(conn, canvasId, " ", "0".repeat(64)));
    }

    // Dedup: request_hash must contain one SHA-256 hex digest.
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_request_hash_length",
          () -> insertDedup(conn, canvasId, "cmd-ok", " "));
    }

    // Sanity: a fully well-formed dedup row inserts.
    try (Connection conn = newConnection()) {
      insertDedup(conn, canvasId, "cmd-" + linkId, "f".repeat(64));
    }
  }

  @Test
  void canvasNodeDeletionCascadesToItsLinks() throws SQLException {
    long canvasId = FIXTURE_IDS.incrementAndGet();
    long sourceNode = FIXTURE_IDS.incrementAndGet();
    long targetNode = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, sourceNode, canvasId);
      insertNode(conn, targetNode, canvasId);
      insertLink(conn, canvasId, sourceNode, targetNode);
    }

    // Verify link exists.
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select count(*) from canvas_link where canvas_id = ?"
                    + " and source_node_id = ? and target_node_id = ?")) {
      ps.setLong(1, canvasId);
      ps.setLong(2, sourceNode);
      ps.setLong(3, targetNode);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertEquals(1L, rs.getLong(1));
      }
    }

    // Delete the source node; ON DELETE CASCADE must remove the link.
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("delete from canvas_node where id = ? and canvas_id = ?")) {
      ps.setLong(1, sourceNode);
      ps.setLong(2, canvasId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select count(*) from canvas_link where canvas_id = ?"
                    + " and source_node_id = ? and target_node_id = ?")) {
      ps.setLong(1, canvasId);
      ps.setLong(2, sourceNode);
      ps.setLong(3, targetNode);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertEquals(0L, rs.getLong(1), "ON DELETE CASCADE must remove the link");
      }
    }
  }

  private void insertProvider(Connection conn, String name) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_provider (name, provider_type, config)"
                + " values (?, 'openai', '{}'::jsonb)")) {
      ps.setString(1, name);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertModel(Connection conn, String providerName, String name) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_model (provider_name, name, config)"
                + " values (?, ?, '{}'::jsonb)")) {
      ps.setString(1, providerName);
      ps.setString(2, name);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDefinition(
      Connection conn, String name, String modelProviderName, String modelName)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_definition (name, model_provider_name, model_name, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, name);
      ps.setString(2, modelProviderName);
      ps.setString(3, modelName);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertChat(Connection conn, long id, String environmentName) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into chat (id, title, agent_name, environment_name)"
                + " values (?, 'schema-test', 'schema-agent', ?)")) {
      ps.setLong(1, id);
      ps.setString(2, environmentName);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertCanvas(Connection conn, long id) throws SQLException {
    insertCanvasRow(conn, id, "schema-test");
  }

  private void insertCanvasRow(Connection conn, long id, String title) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_document (id, title, revision, home_viewport)"
                + " values (?, ?, 0, '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setString(2, title);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNode(Connection conn, long id, long canvasId) throws SQLException {
    insertNodeRow(conn, id, canvasId, "RESOURCE", "node", 100, 100);
  }

  private void insertNodeRow(
      Connection conn,
      long id,
      long canvasId,
      String kind,
      String name,
      double width,
      double height)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, kind, node_type, name, x, y, width, height,"
                + " data) values (?, ?, ?, 'schema-test', ?, 0, 0, ?, ?, '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setLong(2, canvasId);
      ps.setString(3, kind);
      ps.setString(4, name);
      ps.setDouble(5, width);
      ps.setDouble(6, height);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDedup(Connection conn, long canvasId, String commandId, String requestHash)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_command_dedup (canvas_id, command_id, request_hash)"
                + " values (?, ?, ?)")) {
      ps.setLong(1, canvasId);
      ps.setString(2, commandId);
      ps.setString(3, requestHash);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertLink(Connection conn, long canvasId, long sourceNodeId, long targetNodeId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_link (canvas_id, source_node_id, target_node_id)"
                + " values (?, ?, ?)")) {
      ps.setLong(1, canvasId);
      ps.setLong(2, sourceNodeId);
      ps.setLong(3, targetNodeId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
