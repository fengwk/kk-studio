package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** 验证 PostgreSQL schema 是否完全表达非 Harness 的业务数据。 */
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
    for (String invalid : new String[] {" provider ", "\tprovider\t", "provider/name"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn, "ck_agent_provider_name", () -> insertProvider(conn, invalid));
      }
    }
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
          conn, "ck_agent_model_name", () -> insertModel(conn, providerName, "\nmodel\n"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "\tagent\t", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "agent/name", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_definition_model",
          () -> insertDefinition(conn, "missing-agent", "missing-provider", "missing-model"));
    }
  }

  @Test
  void chatAgentNameContractIsCanonicalAndThreadRevisionStaysAppOwned() throws SQLException {
    try (Connection conn = newConnection()) {
      insertChat(conn, FIXTURE_IDS.incrementAndGet());
      insertChat(conn, FIXTURE_IDS.incrementAndGet());
    }

    for (String invalid : new String[] {" ", " agent ", "\tagent\t", "agent/name"}) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "insert into chat (id, title, agent_name) values (?, 'schema-test', ?)")) {
        ps.setLong(1, FIXTURE_IDS.incrementAndGet());
        ps.setString(2, invalid);
        assertTransactionConstraintViolation(conn, "ck_chat_agent_name", () -> ps.executeUpdate());
      }
    }

    long threadId = FIXTURE_IDS.incrementAndGet();
    long sessionId = FIXTURE_IDS.incrementAndGet();
    long entryId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertThread(conn, threadId, sessionId, entryId);
      assertEquals(
          0L, queryLong(conn, "select revision from harness_thread where id = ?", threadId));
      try (PreparedStatement ps =
          conn.prepareStatement("update harness_thread set yolo_enabled = true where id = ?")) {
        ps.setLong(1, threadId);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(
          0L,
          queryLong(conn, "select revision from harness_thread where id = ?", threadId),
          "revision is owned by HarnessRuntime; an application UPDATE must never bump it");
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

    long resourceId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertResource(conn, resourceId, secondCanvasId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_node_resource_resource",
          () -> insertNodeResource(conn, firstCanvasId, firstNodeId, resourceId));
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

    // Document：空白标题被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_document_title_nonblank", () -> insertCanvasRow(conn, canvasId, " "));
    }

    // Node：空白名称被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_name_nonblank",
          () -> insertNodeRow(conn, nodeId, canvasId, "", "", 100, 100));
    }

    // Node：零宽度被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, nodeId, canvasId, "n", "n", 0, 100));
    }

    // Node：零高度被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, nodeId, canvasId, "n", "n", 100, 0));
    }

    // Link：自环被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_link_distinct", () -> insertLink(conn, canvasId, nodeId, nodeId));
    }

    // Dedup：空白 command_id 被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_command_id_nonblank",
          () -> insertDedup(conn, canvasId, " ", "0".repeat(64)));
    }

    // Dedup：request_hash 必须包含一个 SHA-256 十六进制摘要。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_request_hash",
          () -> insertDedup(conn, canvasId, "cmd-ok", " "));
    }

    // 健全性检查：完全合规的 dedup 行可被插入。
    try (Connection conn = newConnection()) {
      insertDedup(conn, canvasId, "cmd-" + linkId, "f".repeat(64));
    }
  }

  @Test
  void canvasNodeDeletionCascadesToItsLinks() throws SQLException {
    long canvasId = FIXTURE_IDS.incrementAndGet();
    long sourceNode = FIXTURE_IDS.incrementAndGet();
    long targetNode = FIXTURE_IDS.incrementAndGet();
    long resourceId = FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, sourceNode, canvasId);
      insertNode(conn, targetNode, canvasId);
      insertResource(conn, resourceId, canvasId);
      insertNodeResource(conn, canvasId, sourceNode, resourceId);
      insertLink(conn, canvasId, sourceNode, targetNode);
    }

    // 校验 link 存在。
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

    // 删除源节点；ON DELETE CASCADE 必须移除该 link。
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
    try (Connection conn = newConnection()) {
      assertEquals(
          1L,
          queryLong(conn, "select count(*) from canvas_resource where id = ?", resourceId),
          "node deletion must retain immutable resources");
      assertEquals(
          0L,
          queryLong(
              conn, "select count(*) from canvas_node_resource where resource_id = ?", resourceId),
          "node deletion must remove only its resource relation");
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

  private void insertChat(Connection conn, long id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into chat (id, title, agent_name)"
                + " values (?, 'schema-test', 'schema-agent')")) {
      ps.setLong(1, id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertThread(Connection conn, long threadId, long sessionId, long entryId)
      throws SQLException {
    try (PreparedStatement session =
            conn.prepareStatement(
                "insert into harness_session (id, title, created_at) values (?, 'schema',"
                    + " current_timestamp)");
        PreparedStatement entry =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
                    + " values (?, ?, 'ROOT', '{}'::jsonb, current_timestamp)");
        PreparedStatement thread =
            conn.prepareStatement(
                "insert into harness_thread (id, head_entry_id, yolo_enabled,"
                    + " next_command_sequence, revision, created_at, updated_at) values"
                    + " (?, ?, false, 1, 0, current_timestamp, current_timestamp)")) {
      session.setLong(1, sessionId);
      assertEquals(1, session.executeUpdate());
      entry.setLong(1, entryId);
      entry.setLong(2, sessionId);
      assertEquals(1, entry.executeUpdate());
      thread.setLong(1, threadId);
      thread.setLong(2, entryId);
      assertEquals(1, thread.executeUpdate());
    }
  }

  private long queryLong(Connection conn, String sql, long id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setLong(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void insertCanvas(Connection conn, long id) throws SQLException {
    insertCanvasRow(conn, id, "schema-test");
  }

  private void insertCanvasRow(Connection conn, long id, String title) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_document (id, title, graph_revision) values (?, ?, 0)")) {
      ps.setLong(1, id);
      ps.setString(2, title);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNode(Connection conn, long id, long canvasId) throws SQLException {
    String name = "node-" + id;
    insertNodeRow(conn, id, canvasId, name, name, 100, 100);
  }

  private void insertNodeRow(
      Connection conn,
      long id,
      long canvasId,
      String name,
      String nameNormalized,
      double width,
      double height)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, name, name_normalized, x, y, width, height)"
                + " values (?, ?, ?, ?, 0, 0, ?, ?)")) {
      ps.setLong(1, id);
      ps.setLong(2, canvasId);
      ps.setString(3, name);
      ps.setString(4, nameNormalized);
      ps.setDouble(5, width);
      ps.setDouble(6, height);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDedup(Connection conn, long canvasId, String commandId, String requestHash)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_command_dedup"
                + " (canvas_id, command_id, request_hash, applied_revision)"
                + " values (?, ?, ?, 0)")) {
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

  private void insertResource(Connection conn, long id, long canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_resource"
                + " (id, canvas_id, kind, media_type, name, size, metadata_json)"
                + " values (?, ?, 'IMAGE', 'image/png', 'image', 1, '{}'::jsonb)")) {
      ps.setLong(1, id);
      ps.setLong(2, canvasId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNodeResource(Connection conn, long canvasId, long nodeId, long resourceId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node_resource"
                + " (canvas_id, node_id, resource_index, resource_id) values (?, ?, 0, ?)")) {
      ps.setLong(1, canvasId);
      ps.setLong(2, nodeId);
      ps.setLong(3, resourceId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
