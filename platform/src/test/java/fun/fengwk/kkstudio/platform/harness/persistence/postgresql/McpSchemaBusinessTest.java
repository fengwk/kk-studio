package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * MCP 表的数据库边界契约：server name 正则、timeout 正数、version 非负、updated_at ≥ created_at、 tool 的 FK
 * 级联硬删除、(server, source_name) 与 model_name 唯一性、description 非空白、input_schema 必须 JSON object。
 */
class McpSchemaBusinessTest extends PostgresSchemaSupport {

  private UUID serverId;

  @BeforeEach
  void setup() throws SQLException {
    // 基类不携带迁移逻辑：每个测试前重建空 schema 并应用 baseline。
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    serverId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      insertServer(conn, serverId, "tools", "https://mcp.example.com/mcp", null, 30000);
    }
  }

  @Test
  void serverNameCheckEnforcesLowercaseIdentifier() throws SQLException {
    for (String invalid : new String[] {"Tools", "1tools", "to ols", "tools-x", ""}) {
      try (Connection conn = newConnection()) {
        UUID id = UUID.randomUUID();
        assertTransactionConstraintViolation(
            conn,
            "ck_mcp_server_name",
            () -> insertServer(conn, id, invalid, "https://mcp.example.com/mcp", null, 30000));
      }
    }
    try (Connection conn = newConnection()) {
      insertServer(conn, UUID.randomUUID(), "tools_2", "https://mcp.example.com/mcp", null, 1);
    }
  }

  @Test
  void serverTimeoutMustBePositive() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () ->
              insertServer(
                  conn, UUID.randomUUID(), "zero", "https://mcp.example.com/mcp", null, 0));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () ->
              insertServer(
                  conn, UUID.randomUUID(), "negative", "https://mcp.example.com/mcp", null, -5));
    }
  }

  @Test
  void serverNameIsUnique() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_mcp_server_name",
          () ->
              insertServer(conn, UUID.randomUUID(), "tools", "https://other.example.com", null, 5));
    }
  }

  @Test
  void toolRequiresKnownParentAndCascadesHardDelete() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_mcp_tool_server",
          () -> insertTool(conn, UUID.randomUUID(), UUID.randomUUID(), "list", "mcp_tools_list"));
    }
    UUID toolId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      insertTool(conn, serverId, toolId, "list", "mcp_tools_list");
    }
    // 级联硬删除：删除父 server 行后工具行物理消失。
    try (Connection conn = newConnection();
        PreparedStatement ps = conn.prepareStatement("delete from mcp_server where id = ?")) {
      ps.setObject(1, serverId);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select count(*) from mcp_tool where id = ?")) {
      ps.setObject(1, toolId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong(1));
      }
    }
  }

  @Test
  void toolSourceNameAndModelNameAreUnique() throws SQLException {
    try (Connection conn = newConnection()) {
      insertTool(conn, serverId, UUID.randomUUID(), "list", "mcp_tools_list");
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_mcp_tool_server_source_name",
          () -> insertTool(conn, serverId, UUID.randomUUID(), "list", "mcp_tools_list_2"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_mcp_tool_model_name",
          () -> insertTool(conn, serverId, UUID.randomUUID(), "list2", "mcp_tools_list"));
    }
  }

  @Test
  void toolDescriptionAndSchemaChecks() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_tool_description_nonblank",
          () ->
              insertToolWithSchema(
                  conn, serverId, UUID.randomUUID(), "blank-desc", "mcp_tools_blank", "   ", "{}"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_tool_input_schema_object",
          () ->
              insertToolWithSchema(
                  conn,
                  serverId,
                  UUID.randomUUID(),
                  "array-schema",
                  "mcp_tools_array",
                  "desc",
                  "[]"));
    }
    try (Connection conn = newConnection()) {
      insertToolWithSchema(conn, serverId, UUID.randomUUID(), "ok", "mcp_tools_ok", "desc", "{}");
    }
  }

  @Test
  void serverTimeOrderAndVersionChecks() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_time_order",
          () -> {
            try (Statement inner = conn.createStatement()) {
              inner.execute(
                  "insert into mcp_server (id, name, url, timeout_millis, created_at, updated_at)"
                      + " values ('"
                      + UUID.randomUUID()
                      + "', 'timeorder', 'https://mcp.example.com/mcp', 1,"
                      + " current_timestamp + interval '1 hour', current_timestamp)");
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_version_nonneg",
          () -> {
            try (Statement inner = conn.createStatement()) {
              inner.execute(
                  "insert into mcp_server (id, name, url, timeout_millis, version)"
                      + " values ('"
                      + UUID.randomUUID()
                      + "', 'negversion', 'https://mcp.example.com/mcp', 1, -1)");
            }
          });
    }
  }

  private static void insertServer(
      Connection conn, UUID id, String name, String url, String bearerToken, long timeoutMillis)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_server (id, name, url, bearer_token, timeout_millis) values (?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, name);
      ps.setString(3, url);
      ps.setString(4, bearerToken);
      ps.setLong(5, timeoutMillis);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static void insertTool(
      Connection conn, UUID serverId, UUID toolId, String sourceName, String modelName)
      throws SQLException {
    insertToolWithSchema(
        conn,
        serverId,
        toolId,
        sourceName,
        modelName,
        "description",
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
  }

  private static void insertToolWithSchema(
      Connection conn,
      UUID serverId,
      UUID toolId,
      String sourceName,
      String modelName,
      String description,
      String inputSchema)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_tool (id, mcp_server_id, source_name, model_name, description, input_schema)"
                + " values (?, ?, ?, ?, ?, cast(? as jsonb))")) {
      ps.setObject(1, toolId);
      ps.setObject(2, serverId);
      ps.setString(3, sourceName);
      ps.setString(4, modelName);
      ps.setString(5, description);
      ps.setString(6, inputSchema);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
