package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Platform MCP Server 核心服务集成测试。
 *
 * <p>基于真实的 PostgreSQL Testcontainer 与轻量 Streamable HTTP MCP Server Mock， 验证严格事务保存流程、CAS 并发控制、三态
 * Bearer Token、工具身份跨发现周期稳定、 工具移除/Server 删除时的 Agent 引用拦截。
 */
public class McpServerServiceTest extends PostgresSpringTestSupport {

  @Autowired private McpServerService mcpServerService;
  @Autowired private McpServerRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private FakeStreamableHttpMcpServer fakeServer;

  @BeforeEach
  void setUpServer() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
  }

  @AfterEach
  void tearDownServer() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  public void createsServerWithToolsAndRedactsBearerToken() {
    // 意图：验证创建成功，原始 source_name 被原样保存而 model_name 规范化，工具列表落库，DTO 掩盖 token
    fakeServer.addTool("Search-Files.v1", "Search files by glob", "{\"type\":\"object\"}");
    fakeServer.addTool("read_file", "Read file content", "{\"type\":\"object\"}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("github_core");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setBearerToken("secret_token_123");
    createDTO.setTimeoutMillis(10000L);

    McpServerDTO server = mcpServerService.createServer(createDTO);
    assertNotNull(server);
    assertEquals("github_core", server.getName());
    assertEquals(fakeServer.endpointUrl(), server.getUrl());
    assertTrue(server.isBearerTokenConfigured());
    assertEquals("0", server.getVersion());
    assertEquals(10000L, server.getTimeoutMillis());

    UUID serverId = UUID.fromString(server.getId());
    List<McpTool> tools = repository.listTools(serverId);
    assertEquals(2, tools.size());
    McpTool rawTool =
        tools.stream()
            .filter(t -> t.getSourceName().equals("Search-Files.v1"))
            .findFirst()
            .orElseThrow();
    assertEquals("Search-Files.v1", rawTool.getSourceName()); // 原始名称未被改写！
    assertEquals("mcp_github_core_search_files_v1", rawTool.getModelName()); // 规范化 model_name
    assertTrue(tools.stream().anyMatch(t -> t.getModelName().equals("mcp_github_core_read_file")));

    // 确认名称唯一性拒绝
    assertThrows(AiDuplicateException.class, () -> mcpServerService.createServer(createDTO));
  }

  @Test
  public void rejectsBlankOrOverlongRawSourceNameOnDiscovery() {
    // 意图：验证远端原始名称为空白或超过 128 字符时，发现直接拒绝且不落库
    fakeServer.addTool("   ", "Blank tool name", "{}");

    McpServerCreateDTO createBlank = new McpServerCreateDTO();
    createBlank.setName("blank_tool_srv");
    createBlank.setUrl(fakeServer.endpointUrl());
    createBlank.setTimeoutMillis(5000L);

    assertThrows(AiValidationException.class, () -> mcpServerService.createServer(createBlank));

    fakeServer.clearTools();
    fakeServer.addTool("a".repeat(129), "Overlong tool name", "{}");

    McpServerCreateDTO createOverlong = new McpServerCreateDTO();
    createOverlong.setName("overlong_tool_srv");
    createOverlong.setUrl(fakeServer.endpointUrl());
    createOverlong.setTimeoutMillis(5000L);

    assertThrows(AiValidationException.class, () -> mcpServerService.createServer(createOverlong));
  }

  @Test
  public void rejectsDuplicateRawSourceNames() {
    // 意图：远端返回两个相同原始名称的工具，直接拒绝且不落库
    fakeServer.addTool("fetch_data", "First", "{}");
    fakeServer.addTool("fetch_data", "Second duplicate", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("dup_source_srv");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> mcpServerService.createServer(createDTO));
    assertTrue(ex.getMessage().contains("duplicate tools for source name"));
  }

  @Test
  public void outsideTransactionDiscoveryFailureLeavesDatabaseUntouched() {
    // 意图：验证发现失败（如远端 500 或连接不可达）时，事务外直接失败，数据库完全不插入 server 行
    fakeServer.setFailDiscovery(true);

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("failing_srv");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    assertThrows(AiValidationException.class, () -> mcpServerService.createServer(createDTO));

    Integer count =
        jdbc.queryForObject(
            "select count(*) from mcp_server where name = 'failing_srv'", Integer.class);
    assertEquals(0, count);
  }

  @Test
  public void rejectsConflictingNormalizedModelNames() {
    // 意图：远端返回两个工具规范化后同名（如 search_files 与 search-files），直接拒绝且不落库
    fakeServer.addTool("search_files", "First", "{}");
    fakeServer.addTool("search-files", "Second", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("conflict_srv");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    assertThrows(AiValidationException.class, () -> mcpServerService.createServer(createDTO));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from mcp_server where name = 'conflict_srv'", Integer.class));
  }

  @Test
  public void updatesServerWithTriStateBearerTokenAndCasVersion() {
    // 意图：验证三态 Bearer Token（null 保留、"" 清除、非空替换）与 CAS 版本号递增
    fakeServer.addTool("ping", "Ping", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("token_test");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setBearerToken("initial_token");
    createDTO.setTimeoutMillis(5000L);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    assertTrue(created.isBearerTokenConfigured());
    assertEquals("0", created.getVersion());

    // 1. 更新 timeout，token 传 null -> 保留现有 token
    McpServerUpdateDTO updateKeep = new McpServerUpdateDTO();
    updateKeep.setTimeoutMillis(8000L);
    updateKeep.setExpectedVersion("0");
    McpServerDTO updated1 = mcpServerService.updateServer(created.getId(), updateKeep);
    assertTrue(updated1.isBearerTokenConfigured());
    assertEquals("1", updated1.getVersion());
    assertEquals(8000L, updated1.getTimeoutMillis());

    // 2. 版本冲突检查
    assertThrows(
        AiVersionConflictException.class,
        () -> mcpServerService.updateServer(created.getId(), updateKeep));

    // 3. token 传 "" -> 清除 token
    McpServerUpdateDTO updateClear = new McpServerUpdateDTO();
    updateClear.setBearerToken("");
    updateClear.setExpectedVersion("1");
    McpServerDTO updated2 = mcpServerService.updateServer(created.getId(), updateClear);
    assertFalse(updated2.isBearerTokenConfigured());
    assertEquals("2", updated2.getVersion());

    // 4. token 传 "new_secret" -> 替换 token
    McpServerUpdateDTO updateReplace = new McpServerUpdateDTO();
    updateReplace.setBearerToken("new_secret");
    updateReplace.setExpectedVersion("2");
    McpServerDTO updated3 = mcpServerService.updateServer(created.getId(), updateReplace);
    assertTrue(updated3.isBearerTokenConfigured());
    assertEquals("3", updated3.getVersion());
  }

  @Test
  public void updateAndRefreshPreservesToolIdentityAndHandlesRemoval() {
    // 意图：验证刷新/更新时按 (serverId, sourceName) 稳定保留 UUID，新增工具获新 UUID，移除工具被硬删除
    fakeServer.addTool("tool_a", "Tool A v1", "{}");
    fakeServer.addTool("tool_b", "Tool B", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("sync_test");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    UUID serverId = UUID.fromString(created.getId());

    List<McpTool> toolsV1 = repository.listTools(serverId);
    assertEquals(2, toolsV1.size());
    McpTool toolA1 =
        toolsV1.stream().filter(t -> t.getSourceName().equals("tool_a")).findFirst().get();
    UUID toolAId = toolA1.getId();

    // 远端变更：tool_a 修改描述，tool_b 移除，新增 tool_c
    fakeServer.clearTools();
    fakeServer.addTool("tool_a", "Tool A updated description", "{\"type\":\"object\"}");
    fakeServer.addTool("tool_c", "Tool C", "{}");

    McpServerDTO refreshed = mcpServerService.refreshServer(created.getId(), "0");
    assertEquals("1", refreshed.getVersion());

    List<McpTool> toolsV2 = repository.listTools(serverId);
    assertEquals(2, toolsV2.size());

    McpTool toolA2 =
        toolsV2.stream().filter(t -> t.getSourceName().equals("tool_a")).findFirst().get();
    assertEquals(toolAId, toolA2.getId()); // UUID 保持稳定！
    assertEquals("Tool A updated description", toolA2.getDescription());

    assertTrue(toolsV2.stream().noneMatch(t -> t.getSourceName().equals("tool_b"))); // tool_b 已删除
    assertTrue(toolsV2.stream().anyMatch(t -> t.getSourceName().equals("tool_c"))); // tool_c 已新增
  }

  @Test
  public void blocksToolRemovalAndServerDeleteWhenReferencedByAgentDefinition() {
    // 意图：当任一 agent_definition 引用工具的 AgentToolId 时，刷新移除或 Server 删除均被 AiInUseException 拦截
    fakeServer.addTool("critical_tool", "Critical", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("inuse_test");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    UUID serverId = UUID.fromString(created.getId());
    McpTool tool = repository.listTools(serverId).get(0);
    String agentToolId = McpStableIds.agentToolId(tool.getId()).value();

    // 模拟存在 agent_definition 引用了该 agentToolId
    insertFakeAgentDefinitionReferencingTool(agentToolId);

    // 1. 删除 Server 被拦截
    assertThrows(AiInUseException.class, () -> mcpServerService.deleteServer(created.getId(), "0"));

    // 2. 远端移除该工具后触发 refresh 被拦截
    fakeServer.clearTools();
    assertThrows(
        AiInUseException.class, () -> mcpServerService.refreshServer(created.getId(), "0"));

    // 3. 清理 agent_definition 引用后，delete 成功，且级联删除工具行
    jdbc.update("delete from agent_definition where name = 'test_agent_mcp'");
    mcpServerService.deleteServer(created.getId(), "0");

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from mcp_server where id = ?", Integer.class, serverId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from mcp_tool where mcp_server_id = ?", Integer.class, serverId));
  }

  @Test
  public void getAndPageServers() {
    // 意图：验证 getServer 与分页查询功能
    fakeServer.addTool("dummy", "Dummy", "{}");

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("page_test");
    createDTO.setUrl(fakeServer.endpointUrl());
    createDTO.setTimeoutMillis(5000L);

    McpServerDTO created = mcpServerService.createServer(createDTO);

    McpServerDTO retrieved = mcpServerService.getServer(created.getId());
    assertEquals(created.getId(), retrieved.getId());
    assertEquals("page_test", retrieved.getName());

    assertThrows(
        AiResourceNotFoundException.class,
        () -> mcpServerService.getServer(UUID.randomUUID().toString()));

    Page<McpServerDTO> page = mcpServerService.pageServers(new PageQuery(1, 10));
    assertNotNull(page);
    assertTrue(page.getResults().stream().anyMatch(s -> s.getId().equals(created.getId())));
  }

  @Test
  public void translatesDatabaseModelNameDuplicateKeyExceptionToValidationException() {
    // 意图：验证事务写路径触发 mcp_tool.model_name 唯一索引冲突时，翻译为稳定的 AiValidationException 且回滚
    fakeServer.addTool("echo_dup", "Echo duplicate", "{}");

    UUID srvManualId = UUID.randomUUID();
    jdbc.update(
        "insert into mcp_server (id, name, url, timeout_millis) values (?, 'srv_manual', 'http://127.0.0.1/mcp', 5000)",
        srvManualId);

    // 在 DB 中预先插入占用 mcp_srv_manual_echo_dup 的工具（挂在另一个 server 上）
    UUID thirdServerId = UUID.randomUUID();
    jdbc.update(
        "insert into mcp_server (id, name, url, timeout_millis) values (?, 'srv_third', 'http://127.0.0.1/mcp', 5000)",
        thirdServerId);
    jdbc.update(
        "insert into mcp_tool (id, mcp_server_id, source_name, model_name, description, input_schema) "
            + "values (?, ?, 'echo_dup', 'mcp_srv_manual_echo_dup', 'Conflict tool', '{}'::jsonb)",
        UUID.randomUUID(),
        thirdServerId);

    McpServerUpdateDTO updateDTO = new McpServerUpdateDTO();
    updateDTO.setUrl(fakeServer.endpointUrl());
    updateDTO.setExpectedVersion("0");

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> mcpServerService.updateServer(srvManualId.toString(), updateDTO));
    assertTrue(ex.getMessage().contains("mcp tool model name conflicts with an existing tool"));
  }

  private void insertFakeAgentDefinitionReferencingTool(String agentToolId) {
    // 插入关联的 Provider 与 Model 满足 FK 约束
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) values ('test_p_mcp', 'openai', '{}'::jsonb, '00000000-0000-0000-0000-000000000099'::uuid) on conflict do nothing");
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values ('test_p_mcp', 'm1', 'm1', '{}'::jsonb) on conflict do nothing");

    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values ('test_agent_mcp', 'test_p_mcp', 'm1', "
            + "cast('{\"toolIds\": [\""
            + agentToolId
            + "\"]}' as jsonb))");
  }
}
