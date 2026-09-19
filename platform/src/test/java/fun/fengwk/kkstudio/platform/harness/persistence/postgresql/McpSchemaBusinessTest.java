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

/**
 * MCP 表的数据库边界契约：server name 正则与唯一性、url 非空白、timeout 正数、version 非负、updated_at ≥ created_at、
 * discovery_status 枚举、headers 必须 JSONB object、tool 的 FK 级联硬删除、(server_name, source_name) 唯一性、
 * description 非空白、input_schema 必须 JSON object。
 *
 * <p>name 即主键，已不存在 UUID、connection_type、connection_config、discovered_version 与 schema_revision。
 */
class McpSchemaBusinessTest extends PostgresSchemaSupport {

  private static final String SERVER_NAME = "tools";

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    try (Connection conn = newConnection()) {
      insertServer(conn, SERVER_NAME, "https://mcp.example.com/mcp", 30000);
    }
  }

  @Test
  void serverNameCheckEnforcesLowercaseIdentifier() throws SQLException {
    for (String invalid : new String[] {"Tools", "1tools", "to ols", "tools-x", ""}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "ck_mcp_server_name",
            () -> insertServer(conn, invalid, "https://a.example.com", 1));
      }
    }
    try (Connection conn = newConnection()) {
      insertServer(conn, "tools_2", "https://a.example.com", 1);
    }
  }

  @Test
  void serverTimeoutMustBePositive() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () -> insertServer(conn, "zero", "https://a.example.com", 0));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () -> insertServer(conn, "negative", "https://a.example.com", -5));
    }
  }

  @Test
  void serverNameIsPrimaryKeyAndUnique() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "mcp_server_pkey",
          () -> insertServer(conn, SERVER_NAME, "https://a.example.com", 5));
    }
  }

  @Test
  void serverUrlHeadersAndStatusChecks() throws SQLException {
    // url 不允许空白
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_mcp_server_url_nonblank", () -> insertServer(conn, "badurl", "   ", 30000));
    }
    // headers 必须是 JSONB object
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_headers_object",
          () ->
              insertServerRaw(
                  conn,
                  "badheaders",
                  "https://a.example.com",
                  "[\"array\"]",
                  30000,
                  "UNVERIFIED",
                  true,
                  0));
    }
    // discovery_status 枚举
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_discovery_status",
          () ->
              insertServerRaw(
                  conn,
                  "badstatus",
                  "https://a.example.com",
                  "{}",
                  30000,
                  "INVALID_STATUS",
                  true,
                  0));
    }
  }

  @Test
  void serverTimeOrderAndVersionChecks() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_time_order",
          () -> {
            try (Statement st = conn.createStatement()) {
              st.execute(
                  "insert into mcp_server (name, url, timeout_millis, created_at, updated_at)"
                      + " values ('timeorder', 'https://a.example.com', 1,"
                      + " current_timestamp + interval '1 hour', current_timestamp)");
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_version_nonneg",
          () -> {
            try (Statement st = conn.createStatement()) {
              st.execute(
                  "insert into mcp_server (name, url, timeout_millis, version)"
                      + " values ('negversion', 'https://a.example.com', 1, -1)");
            }
          });
    }
  }

  @Test
  void toolRequiresKnownParentAndCascadesHardDelete() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_mcp_tool_server",
          () -> insertTool(conn, "unknown_server", "list", "mcp_unknown_server_list"));
    }
    try (Connection conn = newConnection()) {
      insertTool(conn, SERVER_NAME, "list", "mcp_tools_list");
    }
    // 级联硬删除：删除父 server 行后工具行物理消失。
    try (Connection conn = newConnection();
        PreparedStatement ps = conn.prepareStatement("delete from mcp_server where name = ?")) {
      ps.setString(1, SERVER_NAME);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select count(*) from mcp_tool where name = ?")) {
      ps.setString(1, "mcp_tools_list");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong(1));
      }
    }
  }

  @Test
  void toolSourceNameAndModelNameAreUnique() throws SQLException {
    try (Connection conn = newConnection()) {
      insertTool(conn, SERVER_NAME, "list", "mcp_tools_list");
    }
    // 同一 server 下 source_name 唯一
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_mcp_tool_server_source_name",
          () -> insertTool(conn, SERVER_NAME, "list", "mcp_tools_list_2"));
    }
    // 模型可见 name 是主键：全局唯一
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "mcp_tool_pkey", () -> insertTool(conn, SERVER_NAME, "list2", "mcp_tools_list"));
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
                  conn, SERVER_NAME, "blank-desc", "mcp_tools_blank", "   ", "{}"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_tool_input_schema_object",
          () ->
              insertToolWithSchema(
                  conn, SERVER_NAME, "array-schema", "mcp_tools_array", "desc", "[]"));
    }
    try (Connection conn = newConnection()) {
      insertToolWithSchema(conn, SERVER_NAME, "ok", "mcp_tools_ok", "desc", "{}");
    }
  }

  private static void insertServer(Connection conn, String name, String url, long timeoutMillis)
      throws SQLException {
    insertServerRaw(conn, name, url, "{}", timeoutMillis, "UNVERIFIED", true, 0);
  }

  private static void insertServerRaw(
      Connection conn,
      String name,
      String url,
      String headers,
      long timeoutMillis,
      String discoveryStatus,
      boolean enabled,
      long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_server (name, url, headers, enabled, timeout_millis, discovery_status, version)"
                + " values (?, ?, cast(? as jsonb), ?, ?, ?, ?)")) {
      ps.setString(1, name);
      ps.setString(2, url);
      ps.setString(3, headers);
      ps.setBoolean(4, enabled);
      ps.setLong(5, timeoutMillis);
      ps.setString(6, discoveryStatus);
      ps.setLong(7, version);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static void insertTool(
      Connection conn, String serverName, String sourceName, String modelName) throws SQLException {
    insertToolWithSchema(
        conn,
        serverName,
        sourceName,
        modelName,
        "description",
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
  }

  private static void insertToolWithSchema(
      Connection conn,
      String serverName,
      String sourceName,
      String modelName,
      String description,
      String inputSchema)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_tool (name, server_name, source_name, description, input_schema)"
                + " values (?, ?, ?, ?, cast(? as jsonb))")) {
      ps.setString(1, modelName);
      ps.setString(2, serverName);
      ps.setString(3, sourceName);
      ps.setString(4, description);
      ps.setString(5, inputSchema);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
