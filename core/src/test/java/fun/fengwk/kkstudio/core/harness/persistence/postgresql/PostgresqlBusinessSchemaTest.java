package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Verifies that non-Harness business data is fully represented by the PostgreSQL schema. */
class PostgresqlBusinessSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
    }
  }

  @Test
  void agentResourcesKeepProviderAndModelOwnership() throws SQLException {
    long providerId = FIXTURE_IDS.incrementAndGet();
    long modelId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertProvider(conn, providerId);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_model_provider",
          () -> insertModel(conn, modelId, providerId + 1_000_000L));
    }
    try (Connection conn = newConnection()) {
      insertModel(conn, modelId, providerId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_definition_model",
          () -> insertDefinition(conn, FIXTURE_IDS.incrementAndGet(), modelId + 1_000_000L));
    }
  }

  @Test
  void canvasRelationsCannotCrossWorkspaceOrCanvasBoundaries() throws SQLException {
    long firstCanvasId = FIXTURE_IDS.incrementAndGet();
    long secondCanvasId = FIXTURE_IDS.incrementAndGet();
    long firstNodeId = FIXTURE_IDS.incrementAndGet();
    long secondNodeId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, firstCanvasId, 10L);
      insertCanvas(conn, secondCanvasId, 20L);
      insertNode(conn, firstNodeId, firstCanvasId, "GROUP", null);
      insertNode(conn, secondNodeId, secondCanvasId, "GROUP", null);
    }

    try (Connection conn = newConnection()) {
      long selfId = FIXTURE_IDS.incrementAndGet();
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_parent_not_self",
          () -> insertNode(conn, selfId, firstCanvasId, "GROUP", selfId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_node_parent_group",
          () ->
              insertNode(
                  conn, FIXTURE_IDS.incrementAndGet(), firstCanvasId, "RESOURCE", secondNodeId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_link_target",
          () -> insertLink(conn, firstCanvasId, firstNodeId, secondNodeId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_canvas_command_canvas", () -> insertCanvasCommand(conn, 20L, firstCanvasId));
    }
  }

  @Test
  void chatMembershipRequiresExistingChatAndSessionAndStaysUnique() throws SQLException {
    long chatId = FIXTURE_IDS.incrementAndGet();
    ThreadFixture session = ThreadFixture.insertFresh();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("insert into chat (id, title) values (?, 'schema-test')")) {
      ps.setLong(1, chatId);
      assertEquals(1, ps.executeUpdate());
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_chat_session_chat",
          () -> insertChatSession(conn, chatId + 1_000_000L, session.sessionId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_chat_session_session",
          () -> insertChatSession(conn, chatId, session.sessionId + 1_000_000L));
    }
    try (Connection conn = newConnection()) {
      insertChatSession(conn, chatId, session.sessionId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "uk_chat_session", () -> insertChatSession(conn, chatId, session.sessionId));
    }
  }

  private void insertProvider(Connection conn, long id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_provider (id, name, provider_type, config)"
                + " values (?, ?, 'openai', '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setString(2, "provider-" + id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertModel(Connection conn, long id, long providerId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_model (id, provider_id, name, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setLong(2, providerId);
      ps.setString(3, "model-" + id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDefinition(Connection conn, long id, long modelId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_definition (id, name, model_id, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setString(2, "agent-" + id);
      ps.setLong(3, modelId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertCanvas(Connection conn, long id, long workspaceId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_document (id, workspace_id, title, schema_version, revision,"
                + " lifecycle, home_viewport) values (?, ?, 'schema-test', 1, 0, 'ACTIVE',"
                + " '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setLong(2, workspaceId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNode(Connection conn, long id, long canvasId, String kind, Long parentGroupId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, kind, node_type, node_type_version, name,"
                + " parent_group_id, x, y, width, height, rotation, z_index, locked, hidden,"
                + " validity, data, revision) values (?, ?, ?, 'schema-test', 1, 'node', ?,"
                + " 0, 0, 100, 100, 0, 0, false, false, 'VALID', '{}'::jsonb, 0)")) {
      ps.setLong(1, id);
      ps.setLong(2, canvasId);
      ps.setString(3, kind);
      if (parentGroupId == null) {
        ps.setNull(4, Types.BIGINT);
      } else {
        ps.setLong(4, parentGroupId);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertLink(Connection conn, long canvasId, long sourceNodeId, long targetNodeId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_link (canvas_id, source_node_id, target_node_id, revision)"
                + " values (?, ?, ?, 0)")) {
      ps.setLong(1, canvasId);
      ps.setLong(2, sourceNodeId);
      ps.setLong(3, targetNodeId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertCanvasCommand(Connection conn, long workspaceId, long canvasId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_command (command_id, workspace_id, canvas_id, base_revision,"
                + " result_revision, request_hash, payload, result) values (?, ?, ?, 0, 1,"
                + " 'hash', '{}'::jsonb, '{}'::jsonb)")) {
      ps.setString(1, "command-" + FIXTURE_IDS.incrementAndGet());
      ps.setLong(2, workspaceId);
      ps.setLong(3, canvasId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertChatSession(Connection conn, long chatId, long sessionId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("insert into chat_session (chat_id, session_id) values (?, ?)")) {
      ps.setLong(1, chatId);
      ps.setLong(2, sessionId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
