package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpServerDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpToolDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code mcp_server} 与 {@code mcp_tool} 的 JdbcClient 入口。 */
@Repository
public class McpServerJdbcStore {

  private static final String SERVER_COLUMNS =
      "name, url, headers::text as headers_json, enabled, timeout_millis, discovery_status, "
          + "version, created_at as create_time, updated_at as update_time";

  private static final String TOOL_COLUMNS =
      "name, server_name, source_name, description, input_schema::text as input_schema_json";

  private static final String INSERT_SERVER_SQL =
      """
      insert into mcp_server (
          name, url, headers, enabled, timeout_millis, discovery_status,
          created_at, updated_at, version
      ) values (
          :name, :url, cast(:headersJson as jsonb), :enabled, :timeoutMillis,
          :discoveryStatus, current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_SERVER_SQL =
      """
      update mcp_server
      set url = :url,
          headers = cast(:headersJson as jsonb),
          enabled = :enabled,
          timeout_millis = :timeoutMillis,
          discovery_status = 'UNVERIFIED',
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final RowMapper<McpServerDO> SERVER_ROW_MAPPER = McpServerJdbcStore::mapServerRow;
  private static final RowMapper<McpToolDO> TOOL_ROW_MAPPER = McpServerJdbcStore::mapToolRow;

  private final JdbcClient jdbcClient;

  public McpServerJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public long count() {
    return jdbcClient.sql("select count(*) from mcp_server").query(Long.class).single();
  }

  public List<McpServerDO> listAllServers() {
    return jdbcClient
        .sql("select " + SERVER_COLUMNS + " from mcp_server order by name asc")
        .query(SERVER_ROW_MAPPER)
        .list();
  }

  public List<McpServerDO> page(long offset, int limit) {
    return jdbcClient
        .sql(
            "select "
                + SERVER_COLUMNS
                + " from mcp_server order by name asc limit :limit offset :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(SERVER_ROW_MAPPER)
        .list();
  }

  public McpServerDO getByName(String name) {
    return findServer("select " + SERVER_COLUMNS + " from mcp_server where name = :name", name);
  }

  public McpServerDO lockForShare(String name) {
    return findServer(
        "select " + SERVER_COLUMNS + " from mcp_server where name = :name for share", name);
  }

  public McpServerDO getForUpdate(String name) {
    return findServer(
        "select " + SERVER_COLUMNS + " from mcp_server where name = :name for update", name);
  }

  public int insert(McpServerDO server) {
    return jdbcClient
        .sql(INSERT_SERVER_SQL)
        .param("name", server.getName())
        .param("url", server.getUrl())
        .param("headersJson", server.getHeadersJson())
        .param("enabled", server.isEnabled())
        .param("timeoutMillis", server.getTimeoutMillis())
        .param("discoveryStatus", server.getDiscoveryStatus())
        .update();
  }

  public int update(McpServerDO server, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_SERVER_SQL)
        .param("url", server.getUrl())
        .param("headersJson", server.getHeadersJson())
        .param("enabled", server.isEnabled())
        .param("timeoutMillis", server.getTimeoutMillis())
        .param("name", server.getName())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int updateDiscoveryStatus(String name, long expectedVersion, String discoveryStatus) {
    return jdbcClient
        .sql(
            """
            update mcp_server
            set discovery_status = :discoveryStatus,
                updated_at = greatest(updated_at, current_timestamp)
            where name = :name and version = :expectedVersion
            """)
        .param("discoveryStatus", discoveryStatus)
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int delete(String name, long expectedVersion) {
    return jdbcClient
        .sql("delete from mcp_server where name = :name and version = :expectedVersion")
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public McpToolDO getTool(String name) {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool where name = :name")
        .param("name", name)
        .query(TOOL_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public McpToolDO lockToolForShare(String name) {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool where name = :name for share")
        .param("name", name)
        .query(TOOL_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<McpToolDO> listTools(String serverName) {
    return jdbcClient
        .sql(
            "select "
                + TOOL_COLUMNS
                + " from mcp_tool where server_name = :serverName order by name asc")
        .param("serverName", serverName)
        .query(TOOL_ROW_MAPPER)
        .list();
  }

  public List<McpToolDO> listAllTools() {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool order by name asc")
        .query(TOOL_ROW_MAPPER)
        .list();
  }

  public int countTools(String serverName) {
    return jdbcClient
        .sql("select count(*) from mcp_tool where server_name = :serverName")
        .param("serverName", serverName)
        .query(Integer.class)
        .single();
  }

  public int deleteTools(String serverName) {
    return jdbcClient
        .sql("delete from mcp_tool where server_name = :serverName")
        .param("serverName", serverName)
        .update();
  }

  public int insertTool(McpToolDO tool) {
    return jdbcClient
        .sql(
            """
            insert into mcp_tool (name, server_name, source_name, description, input_schema)
            values (:name, :serverName, :sourceName, :description, cast(:inputSchemaJson as jsonb))
            """)
        .param("name", tool.getName())
        .param("serverName", tool.getServerName())
        .param("sourceName", tool.getSourceName())
        .param("description", tool.getDescription())
        .param("inputSchemaJson", tool.getInputSchemaJson())
        .update();
  }

  public List<String> selectReferencedToolNames() {
    return jdbcClient
        .sql(
            """
            select distinct tool_name
            from agent_definition,
                 lateral jsonb_array_elements_text(
                   case when jsonb_typeof(config -> 'tools') = 'array'
                        then config -> 'tools'
                        else '[]'::jsonb
                   end
                 ) as tool_name
            where tool_name in (select name from mcp_tool)
            """)
        .query(String.class)
        .list();
  }

  private McpServerDO findServer(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(SERVER_ROW_MAPPER).optional().orElse(null);
  }

  private static McpServerDO mapServerRow(ResultSet rs, int rowNum) throws SQLException {
    McpServerDO row = new McpServerDO();
    row.setName(rs.getString("name"));
    row.setUrl(rs.getString("url"));
    row.setHeadersJson(rs.getString("headers_json"));
    row.setEnabled(rs.getBoolean("enabled"));
    row.setTimeoutMillis(rs.getObject("timeout_millis", Long.class));
    row.setDiscoveryStatus(rs.getString("discovery_status"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }

  private static McpToolDO mapToolRow(ResultSet rs, int rowNum) throws SQLException {
    McpToolDO row = new McpToolDO();
    row.setName(rs.getString("name"));
    row.setServerName(rs.getString("server_name"));
    row.setSourceName(rs.getString("source_name"));
    row.setDescription(rs.getString("description"));
    row.setInputSchemaJson(rs.getString("input_schema_json"));
    return row;
  }
}
