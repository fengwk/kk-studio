package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * V5 MCP JSON/local schema 的既有数据迁移契约。
 *
 * <p>测试意图：证明 V4 中已持久化的 Remote 配置与工具身份会被原位转换，而不是在迁移时丢失、重建或意外禁用。
 */
class PostgresqlMcpMigrationTest extends PostgresSchemaSupport {

  private static final UUID SERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
  private static final String URL = "https://mcp.example.test/rpc";
  private static final String TOKEN = "migration-token";

  @BeforeEach
  void resetSchema() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
    }
  }

  /** 既有 Remote server 与 tool 必须保留身份、连接事实和已发现版本。 */
  @Test
  void migratesLegacyRemoteConfigurationAndToolInventory() throws Exception {
    try (Connection connection = newConnection()) {
      migrateTo(connection, "4");
      insertLegacyServerAndTool(connection);

      migrateTo(connection, "5");

      try (PreparedStatement statement =
          connection.prepareStatement(
              """
                  select id,
                         connection_type,
                         environment_id,
                         connection_config ->> 'url',
                         connection_config -> 'headers' ->> 'Authorization',
                         enabled,
                         discovery_status,
                         discovered_version,
                         version
                  from mcp_server
                  where id = ?
                  """)) {
        statement.setObject(1, SERVER_ID);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertTrue(resultSet.next());
          assertEquals(SERVER_ID, resultSet.getObject("id", UUID.class));
          assertEquals("REMOTE", resultSet.getString("connection_type"));
          assertNull(resultSet.getObject("environment_id"));
          assertEquals(URL, resultSet.getString(4));
          assertEquals("Bearer " + TOKEN, resultSet.getString(5));
          assertTrue(resultSet.getBoolean("enabled"));
          assertEquals("AVAILABLE", resultSet.getString("discovery_status"));
          assertEquals(7L, resultSet.getLong("discovered_version"));
          assertEquals(7L, resultSet.getLong("version"));
          assertFalse(resultSet.next());
        }
      }

      try (PreparedStatement statement =
          connection.prepareStatement(
              """
                  select id, mcp_server_id, source_name, model_name, schema_revision, available
                  from mcp_tool
                  where id = ?
                  """)) {
        statement.setObject(1, TOOL_ID);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertTrue(resultSet.next());
          assertEquals(TOOL_ID, resultSet.getObject("id", UUID.class));
          assertEquals(SERVER_ID, resultSet.getObject("mcp_server_id", UUID.class));
          assertEquals("search", resultSet.getString("source_name"));
          assertEquals("mcp_legacy_search", resultSet.getString("model_name"));
          assertEquals(0L, resultSet.getLong("schema_revision"));
          assertTrue(resultSet.getBoolean("available"));
          assertFalse(resultSet.next());
        }
      }

      assertEquals(0, columnCount(connection, "mcp_server", "url"));
      assertEquals(0, columnCount(connection, "mcp_server", "bearer_token"));
    }
  }

  private static void insertLegacyServerAndTool(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            insert into mcp_server (
                id, name, url, bearer_token, timeout_millis, version
            ) values (?, 'legacy', ?, ?, 60000, 7)
            """)) {
      statement.setObject(1, SERVER_ID);
      statement.setString(2, URL);
      statement.setString(3, TOKEN);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            insert into mcp_tool (
                id, mcp_server_id, source_name, model_name, description, input_schema
            ) values (?, ?, 'search', 'mcp_legacy_search', 'Search tool', '{}'::jsonb)
            """)) {
      statement.setObject(1, TOOL_ID);
      statement.setObject(2, SERVER_ID);
      statement.executeUpdate();
    }
  }

  private static int columnCount(Connection connection, String table, String column)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select count(*)
            from information_schema.columns
            where table_schema = 'public' and table_name = ? and column_name = ?
            """)) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static void migrateTo(Connection connection, String target) {
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(target))
        .outOfOrder(true)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
