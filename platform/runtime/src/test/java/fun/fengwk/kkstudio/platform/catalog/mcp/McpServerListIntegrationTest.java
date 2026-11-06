package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.catalog.dto.McpServerSummaryDTO;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** PostgreSQL 上验证 MCP 列表的稳定排序与一次批量工具计数。 */
class McpServerListIntegrationTest {

  private JdbcClient fixture;
  private JdbcClient observed;
  private McpServerService service;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection connection = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(connection);
      PostgresSchemaSupport.applyBaseline(connection);
    }
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    fixture = JdbcClient.create(dataSource);
    observed = spy(JdbcClient.create(dataSource));
    service =
        new McpServerService(
            new McpServerRepository(observed), new DataSourceTransactionManager(dataSource));
  }

  @Test
  void emptyCatalogNeedsOnlyTheListQuery() {
    // 意图：空表只执行列表查询，不为空集合发明 IN 工具计数语句。
    List<McpServerSummaryDTO> servers = service.listServers();

    assertEquals(List.of(), servers);
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void listsAllServersSortedWithToolCountsFromOneQuery() {
    // 意图：列表按 name 升序返回全部 server，无工具与不同工具数的 server 都投影到同一份批量统计。
    insertServer("delta");
    insertServer("alpha");
    insertServer("charlie");
    insertServer("bravo");
    insertTool("alpha", "one");
    insertTool("alpha", "two");
    insertTool("charlie", "one");
    insertTool("delta", "one");
    insertTool("delta", "two");
    insertTool("delta", "three");

    List<McpServerSummaryDTO> servers = service.listServers();

    assertEquals(List.of("alpha", "bravo", "charlie", "delta"), names(servers));
    assertEquals(List.of(2, 0, 1, 3), counts(servers));
    verify(observed, times(2)).sql(anyString());
    verify(observed, times(1)).sql(contains("mcp_tool"));
    verify(observed, times(1)).sql(contains("order by name asc"));
    verify(observed).sql(contains("group by server_name"));
    verify(observed, times(0)).sql(contains("offset"));
  }

  @Test
  void detailKeepsUrlAndOnlyStatesWhetherHeadersAreConfigured() {
    // 意图：详情是唯一携带 URL 的投影，headers 只以 configured 事实出现。
    fixture
        .sql(
            "insert into mcp_server (name, url, headers, timeout_millis) values (:name, :url, cast(:headers as jsonb), 5000)")
        .param("name", "alpha")
        .param("url", "https://example.com/mcp")
        .param("headers", "{\"Authorization\":\"Bearer secret\"}")
        .update();
    insertTool("alpha", "one");

    var detail = service.getServer("alpha");

    assertEquals("https://example.com/mcp", detail.getUrl());
    assertEquals(true, detail.getHeaders().configured());
    assertEquals(1, detail.getToolCount());
    assertEquals(McpDiscoveryStatus.UNVERIFIED, detail.getDiscoveryStatus());
  }

  private void insertServer(String name) {
    fixture
        .sql("insert into mcp_server (name, url, timeout_millis) values (:name, :url, 5000)")
        .param("name", name)
        .param("url", "https://example.com/mcp")
        .update();
  }

  private void insertTool(String serverName, String sourceName) {
    fixture
        .sql(
            """
            insert into mcp_tool (name, server_name, source_name, description, input_schema)
            values (:name, :serverName, :sourceName, 'test', '{}'::jsonb)
            """)
        .param("name", "mcp_" + serverName + "_" + sourceName)
        .param("serverName", serverName)
        .param("sourceName", sourceName)
        .update();
  }

  private static List<String> names(List<McpServerSummaryDTO> servers) {
    return servers.stream().map(McpServerSummaryDTO::getName).toList();
  }

  private static List<Integer> counts(List<McpServerSummaryDTO> servers) {
    return servers.stream().map(McpServerSummaryDTO::getToolCount).toList();
  }
}
