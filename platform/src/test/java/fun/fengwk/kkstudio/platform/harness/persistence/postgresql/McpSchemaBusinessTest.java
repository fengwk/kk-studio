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
 * MCP 表的数据库边界契约：server name 正则、timeout 正数、version 非负、updated_at ≥ created_at、
 * connection_type/discovery_status 枚举、connection_config JSONB object、LOCAL/REMOTE environment 校验、
 * tool 的 FK 级联硬删除、(server, source_name) 与 model_name 唯一性、description 非空白、input_schema 必须 JSON
 * object、 schema_revision 非负。
 */
class McpSchemaBusinessTest extends PostgresSchemaSupport {

  private UUID serverId;
  private UUID envId;

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    serverId = UUID.randomUUID();
    envId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      insertEnvironment(conn, envId, "test-env");
      insertServer(conn, serverId, "tools", 30000);
    }
  }

  @Test
  void serverNameCheckEnforcesLowercaseIdentifier() throws SQLException {
    for (String invalid : new String[] {"Tools", "1tools", "to ols", "tools-x", ""}) {
      try (Connection conn = newConnection()) {
        UUID id = UUID.randomUUID();
        assertTransactionConstraintViolation(
            conn, "ck_mcp_server_name", () -> insertServer(conn, id, invalid, 30000));
      }
    }
    try (Connection conn = newConnection()) {
      insertServer(conn, UUID.randomUUID(), "tools_2", 1);
    }
  }

  @Test
  void serverTimeoutMustBePositive() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () -> insertServer(conn, UUID.randomUUID(), "zero", 0));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_timeout_positive",
          () -> insertServer(conn, UUID.randomUUID(), "negative", -5));
    }
  }

  @Test
  void serverNameIsUnique() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "uk_mcp_server_name", () -> insertServer(conn, UUID.randomUUID(), "tools", 5));
    }
  }

  @Test
  void serverConnectionTypeAndConfigChecks() throws SQLException {
    // Invalid connection type
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_connection_type",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "invalidtype",
                  "UNKNOWN",
                  null,
                  "{\"url\":\"https://mcp.example.com\",\"headers\":{}}",
                  30000,
                  "UNVERIFIED",
                  0));
    }

    // Connection config must be JSON object
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_connection_config_object",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "badconfig",
                  "REMOTE",
                  null,
                  "[\"array\"]",
                  30000,
                  "UNVERIFIED",
                  0));
    }

    // Invalid discovery status
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_discovery_status",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "badstatus",
                  "REMOTE",
                  null,
                  "{\"url\":\"https://mcp.example.com\",\"headers\":{}}",
                  30000,
                  "INVALID_STATUS",
                  0));
    }
  }

  @Test
  void serverLocalEnvironmentCheck() throws SQLException {
    // LOCAL without environment_id must fail
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_local_environment",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "localnoenv",
                  "LOCAL",
                  null,
                  "{\"command\":[\"npx\"],\"cwd\":\"/tmp\",\"env\":{}}",
                  30000,
                  "UNVERIFIED",
                  0));
    }

    // REMOTE with environment_id must fail
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_server_local_environment",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "remotewithenv",
                  "REMOTE",
                  envId,
                  "{\"url\":\"https://mcp.example.com\",\"headers\":{}}",
                  30000,
                  "UNVERIFIED",
                  0));
    }

    // LOCAL with valid environment_id must succeed
    try (Connection conn = newConnection()) {
      insertServerRaw(
          conn,
          UUID.randomUUID(),
          "localwithenv",
          "LOCAL",
          envId,
          "{\"command\":[\"npx\"],\"cwd\":\"/tmp\",\"env\":{}}",
          30000,
          "UNVERIFIED",
          0);
    }

    // LOCAL with unknown environment_id must fail FK
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_mcp_server_environment",
          () ->
              insertServerRaw(
                  conn,
                  UUID.randomUUID(),
                  "localunknownenv",
                  "LOCAL",
                  UUID.randomUUID(),
                  "{\"command\":[\"npx\"],\"cwd\":\"/tmp\",\"env\":{}}",
                  30000,
                  "UNVERIFIED",
                  0));
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
                  conn,
                  serverId,
                  UUID.randomUUID(),
                  "blank-desc",
                  "mcp_tools_blank",
                  "   ",
                  "{}",
                  0));
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
                  "[]",
                  0));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_mcp_tool_schema_revision_nonneg",
          () ->
              insertToolWithSchema(
                  conn,
                  serverId,
                  UUID.randomUUID(),
                  "neg-rev",
                  "mcp_tools_neg_rev",
                  "desc",
                  "{}",
                  -1));
    }
    try (Connection conn = newConnection()) {
      insertToolWithSchema(
          conn, serverId, UUID.randomUUID(), "ok", "mcp_tools_ok", "desc", "{}", 0);
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
                  "insert into mcp_server (id, name, connection_type, connection_config, timeout_millis, created_at, updated_at)"
                      + " values ('"
                      + UUID.randomUUID()
                      + "', 'timeorder', 'REMOTE',"
                      + " '{\"url\":\"https://mcp.example.com\",\"headers\":{}}', 1,"
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
                  "insert into mcp_server (id, name, connection_type, connection_config, timeout_millis, version)"
                      + " values ('"
                      + UUID.randomUUID()
                      + "', 'negversion', 'REMOTE',"
                      + " '{\"url\":\"https://mcp.example.com\",\"headers\":{}}', 1, -1)");
            }
          });
    }
  }

  private static void insertEnvironment(Connection conn, UUID id, String name) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment (id, name, registration_token) values (?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, name);
      ps.setString(3, "tok-" + name);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static void insertServer(Connection conn, UUID id, String name, long timeoutMillis)
      throws SQLException {
    insertServerRaw(
        conn,
        id,
        name,
        "REMOTE",
        null,
        "{\"url\":\"https://mcp.example.com/mcp\",\"headers\":{}}",
        timeoutMillis,
        "UNVERIFIED",
        0);
  }

  private static void insertServerRaw(
      Connection conn,
      UUID id,
      String name,
      String connectionType,
      UUID environmentId,
      String connectionConfig,
      long timeoutMillis,
      String discoveryStatus,
      long discoveredVersion)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_server (id, name, connection_type, environment_id, connection_config, timeout_millis, discovery_status, discovered_version)"
                + " values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, name);
      ps.setString(3, connectionType);
      ps.setObject(4, environmentId);
      ps.setString(5, connectionConfig);
      ps.setLong(6, timeoutMillis);
      ps.setString(7, discoveryStatus);
      ps.setLong(8, discoveredVersion);
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
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}",
        0);
  }

  private static void insertToolWithSchema(
      Connection conn,
      UUID serverId,
      UUID toolId,
      String sourceName,
      String modelName,
      String description,
      String inputSchema,
      long schemaRevision)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into mcp_tool (id, mcp_server_id, source_name, model_name, description, input_schema, schema_revision)"
                + " values (?, ?, ?, ?, ?, cast(? as jsonb), ?)")) {
      ps.setObject(1, toolId);
      ps.setObject(2, serverId);
      ps.setString(3, sourceName);
      ps.setString(4, modelName);
      ps.setString(5, description);
      ps.setString(6, inputSchema);
      ps.setLong(7, schemaRevision);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
