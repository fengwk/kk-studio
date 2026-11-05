package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.share.mcp.McpServerDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** PostgreSQL 上验证 MCP 分页的快照、边界和批量工具计数。 */
class McpServerPaginationIntegrationTest {

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
  void emptyCatalogNeedsOnlyThePageQuery() {
    // 空表仍返回 total=0，不执行空 IN 工具计数。
    Page<McpServerDTO> page = service.pageServers(new PageQuery(1, 2));
    assertEquals(0, page.getTotalCount());
    assertEquals(List.of(), page.getResults());
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void pagesAreSortedAndToolsAreCountedOncePerNonemptyPage() {
    // 多 server、无工具及不同工具数同时覆盖分组映射；最后一页和越界页保留全表 total。
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

    Page<McpServerDTO> first = service.pageServers(new PageQuery(1, 3));
    assertEquals(1, first.getPageNumber());
    assertEquals(3, first.getPageSize());
    assertEquals(4, first.getTotalCount());
    assertEquals(List.of("alpha", "bravo", "charlie"), names(first));
    assertEquals(List.of(2, 0, 1), counts(first));
    verify(observed, times(2)).sql(anyString());
    verify(observed, times(1)).sql(contains("mcp_tool"));

    reset(observed);
    Page<McpServerDTO> last = service.pageServers(new PageQuery(2, 3));
    assertEquals(4, last.getTotalCount());
    assertEquals(List.of("delta"), names(last));
    assertEquals(List.of(3), counts(last));
    verify(observed, times(2)).sql(anyString());
    verify(observed, times(1)).sql(contains("mcp_tool"));

    reset(observed);
    Page<McpServerDTO> pastEnd = service.pageServers(new PageQuery(Integer.MAX_VALUE, 1000));
    assertEquals(4, pastEnd.getTotalCount());
    assertEquals(List.of(), pastEnd.getResults());
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void pageAndTotalStayTogetherWhenAnotherConnectionCommitsBetweenQueries() {
    // 第二条查询前提交新行：旧的“两条 SQL 取页和总数”会得到混合快照。
    insertServer("alpha");
    insertServer("bravo");
    AtomicInteger statements = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (statements.incrementAndGet() == 2) {
                insertServer("charlie");
              }
              return invocation.callRealMethod();
            })
        .when(observed)
        .sql(anyString());

    Page<McpServerDTO> page = service.pageServers(new PageQuery(1, 10));
    assertEquals(List.of("alpha", "bravo"), names(page));
    assertEquals(2, page.getTotalCount());
    assertEquals(3L, fixture.sql("select count(*) from mcp_server").query(Long.class).single());
    verify(observed, times(2)).sql(anyString());
    verify(observed, times(1)).sql(contains("mcp_tool"));
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

  private static List<String> names(Page<McpServerDTO> page) {
    return page.getResults().stream().map(McpServerDTO::getName).toList();
  }

  private static List<Integer> counts(Page<McpServerDTO> page) {
    return page.getResults().stream().map(McpServerDTO::getToolCount).toList();
  }
}
