package fun.fengwk.kkstudio.platform.catalog.mcp;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code mcp_server} 与当前发现结果 {@code mcp_tool} 的 JdbcClient 仓库。
 *
 * <p>Server 写入以 (name, version) 做原子 CAS；工具行随 Server 的 {@code ON DELETE CASCADE} 物理删除，替换工具行的调用方必须已以
 * {@code FOR UPDATE} 持有父 Server。
 */
@Repository
public class McpServerRepository {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

  private static final String SERVER_COLUMNS =
      "name, url, headers::text as headers_json, enabled, timeout_millis, discovery_status, "
          + "version, created_at as create_time, updated_at as update_time";

  private static final String TOOL_COLUMNS =
      "name, server_name, source_name, description, input_schema::text as input_schema_json";

  private static final RowMapper<McpServer> SERVER_ROW_MAPPER =
      (rs, rowNum) ->
          new McpServer(
              rs.getString("name"),
              rs.getString("url"),
              decodeHeaders(rs.getString("headers_json")),
              rs.getBoolean("enabled"),
              rs.getObject("timeout_millis", Long.class),
              McpDiscoveryStatus.valueOf(rs.getString("discovery_status")),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

  private static final RowMapper<McpTool> TOOL_ROW_MAPPER =
      (rs, rowNum) ->
          new McpTool(
              rs.getString("name"),
              rs.getString("server_name"),
              rs.getString("source_name"),
              rs.getString("description"),
              rs.getString("input_schema_json"));

  private final JdbcClient jdbcClient;

  public McpServerRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public Page<McpServer> page(PageQuery pageQuery) {
    List<McpServer> results =
        jdbcClient
            .sql(
                "select "
                    + SERVER_COLUMNS
                    + " from mcp_server order by name asc limit :limit offset :offset")
            .param("limit", pageQuery.getPageSize())
            .param("offset", pageQuery.getOffset())
            .query(SERVER_ROW_MAPPER)
            .list();
    long total = jdbcClient.sql("select count(*) from mcp_server").query(Long.class).single();
    return new Page<>(pageQuery.getPageNumber(), pageQuery.getPageSize(), results, total);
  }

  /** 返回全部 server 行（按 name 排序）。 */
  public List<McpServer> listAllServers() {
    return jdbcClient
        .sql("select " + SERVER_COLUMNS + " from mcp_server order by name asc")
        .query(SERVER_ROW_MAPPER)
        .list();
  }

  public Optional<McpServer> getByName(String name) {
    return findServer("select " + SERVER_COLUMNS + " from mcp_server where name = :name", name);
  }

  /**
   * 以 {@code FOR SHARE} 持有 Server 行，直到外层事务结束。
   *
   * <p>共享锁与 discover/delete 的 {@code FOR UPDATE} 互斥，拦住工具行替换等非键修改，同时允许并发的 Agent 引用事务共存。
   */
  public Optional<McpServer> lockForShare(String name) {
    return findServer(
        "select " + SERVER_COLUMNS + " from mcp_server where name = :name for share", name);
  }

  /** 返回有效的 Server 并持有其行锁，直到外层事务结束。 */
  public Optional<McpServer> getForUpdate(String name) {
    return findServer(
        "select " + SERVER_COLUMNS + " from mcp_server where name = :name for update", name);
  }

  public boolean create(McpServer server) {
    return jdbcClient
            .sql(
                """
                insert into mcp_server (
                    name, url, headers, enabled, timeout_millis, discovery_status,
                    created_at, updated_at, version
                ) values (
                    :name, :url, cast(:headersJson as jsonb), :enabled, :timeoutMillis,
                    :discoveryStatus, current_timestamp, current_timestamp, 0
                )
                """)
            .param("name", server.name())
            .param("url", server.url())
            .param("headersJson", encodeHeaders(server.headers()))
            .param("enabled", server.enabled())
            .param("timeoutMillis", server.timeoutMillis())
            .param("discoveryStatus", server.discoveryStatus().name())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子全量 CAS 更新；重置为 UNVERIFIED 并将版本 +1。 */
  public boolean update(McpServer server, long expectedVersion) {
    return jdbcClient
            .sql(
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
                """)
            .param("url", server.url())
            .param("headersJson", encodeHeaders(server.headers()))
            .param("enabled", server.enabled())
            .param("timeoutMillis", server.timeoutMillis())
            .param("name", server.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 状态更新；不变更 version。 */
  public boolean updateDiscoveryStatus(
      String name, long expectedVersion, McpDiscoveryStatus status) {
    return jdbcClient
            .sql(
                """
                update mcp_server
                set discovery_status = :discoveryStatus,
                    updated_at = greatest(updated_at, current_timestamp)
                where name = :name and version = :expectedVersion
                """)
            .param("discoveryStatus", status.name())
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS；工具行随 ON DELETE CASCADE 物理删除。 */
  public boolean delete(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from mcp_server where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 按模型可见 name 返回工具行。 */
  public Optional<McpTool> getTool(String name) {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool where name = :name")
        .param("name", name)
        .query(TOOL_ROW_MAPPER)
        .optional();
  }

  /** 以 {@code FOR SHARE} 读取工具行；不存在返回 empty。调用方需已持有其 server 的共享锁。 */
  public Optional<McpTool> lockToolForShare(String name) {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool where name = :name for share")
        .param("name", name)
        .query(TOOL_ROW_MAPPER)
        .optional();
  }

  /** 列出该 server 下全部工具行（按 name 排序）。 */
  public List<McpTool> listTools(String serverName) {
    return jdbcClient
        .sql(
            "select "
                + TOOL_COLUMNS
                + " from mcp_tool where server_name = :serverName order by name asc")
        .param("serverName", serverName)
        .query(TOOL_ROW_MAPPER)
        .list();
  }

  /** 列出全库工具行（按 name 排序）。 */
  public List<McpTool> listAllTools() {
    return jdbcClient
        .sql("select " + TOOL_COLUMNS + " from mcp_tool order by name asc")
        .query(TOOL_ROW_MAPPER)
        .list();
  }

  /** 统计 server 下工具数。 */
  public int countTools(String serverName) {
    return jdbcClient
        .sql("select count(*) from mcp_tool where server_name = :serverName")
        .param("serverName", serverName)
        .query(Integer.class)
        .single();
  }

  /** 物理删除该 server 的全部工具行；调用方事务必须已经以 {@code FOR UPDATE} 持有父 server。 */
  public void deleteTools(String serverName) {
    jdbcClient
        .sql("delete from mcp_tool where server_name = :serverName")
        .param("serverName", serverName)
        .update();
  }

  public void insertTool(McpTool tool) {
    jdbcClient
        .sql(
            """
            insert into mcp_tool (name, server_name, source_name, description, input_schema)
            values (:name, :serverName, :sourceName, :description, cast(:inputSchemaJson as jsonb))
            """)
        .param("name", tool.name())
        .param("serverName", tool.serverName())
        .param("sourceName", tool.sourceName())
        .param("description", tool.description())
        .param("inputSchemaJson", tool.inputSchemaJson())
        .update();
  }

  /** 返回任一 agent_definition.config.tools 引用的 MCP 模型可见工具名集合。 */
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

  private Optional<McpServer> findServer(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(SERVER_ROW_MAPPER).optional();
  }

  private static String encodeHeaders(Map<String, String> headers) {
    try {
      return OBJECT_MAPPER.writeValueAsString(headers == null ? Map.of() : headers);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to encode MCP server headers", error);
    }
  }

  private static Map<String, String> decodeHeaders(String headersJson) {
    if (headersJson == null || headersJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> decoded = OBJECT_MAPPER.readValue(headersJson, STRING_MAP);
      return decoded == null ? Map.of() : Map.copyOf(decoded);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to decode MCP server headers", error);
    }
  }
}
