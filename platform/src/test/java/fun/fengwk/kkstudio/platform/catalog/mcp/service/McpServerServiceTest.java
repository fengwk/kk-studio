package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoveryResponseDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Platform MCP Server 核心服务集成测试。
 *
 * <p>基于真实的 PostgreSQL Testcontainer 与轻量 Streamable HTTP MCP Server Mock， 验证严格保存流程、CAS 并发控制、安全 DTO
 * 投影、分离的显式发现与工具代际 tombstone 机制。
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
  public void createsServerWithoutIoAndExposesSafeDto() {
    // 意图：验证创建时绝不发生网络 IO，初始状态为 UNVERIFIED，DTO 仅暴露安全元数据，通过 getServerConfig 可读取完整配置
    String configJson =
        """
        {
          "type": "remote",
          "url": "%s",
          "headers": {
            "Authorization": "Bearer secret_token_123"
          },
          "timeoutMillis": 10000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("github_core");
    createDTO.setConfigJson(configJson);

    McpServerDTO server = mcpServerService.createServer(createDTO);
    assertNotNull(server);
    assertEquals("github_core", server.getName());
    assertEquals("remote", server.getType());
    assertNull(server.getEnvironmentId());
    assertEquals("0", server.getVersion());
    assertEquals(10000L, server.getTimeoutMillis());
    assertEquals("UNVERIFIED", server.getDiscoveryStatus());
    assertNull(server.getDiscoveredVersion());
    assertEquals(0, server.getToolCount());

    // 验证 getServerConfig 返回完整配置
    McpServerConfigDTO configDTO = mcpServerService.getServerConfig(server.getId());
    assertEquals(server.getId(), configDTO.getId());
    assertEquals("github_core", configDTO.getName());
    assertTrue(configDTO.getConfigJson().contains("secret_token_123"));

    // 确认名称唯一性拒绝
    assertThrows(AiDuplicateException.class, () -> mcpServerService.createServer(createDTO));
  }

  @Test
  public void discoversRemoteToolsSynchronouslyAndUpdatesRevisions() {
    // 意图：验证 Remote 显式发现同步执行，更新 status 为 AVAILABLE，discovered_version 对齐，支持模式修订与 tombstone
    fakeServer.addTool("Search-Files.v1", "Search files by glob", "{\"type\":\"object\"}");
    fakeServer.addTool("read_file", "Read file content", "{\"type\":\"object\"}");

    String configJson =
        """
        {
          "type": "remote",
          "url": "%s",
          "headers": {
            "Authorization": "Bearer secret_token"
          },
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("remote_sync");
    createDTO.setConfigJson(configJson);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    UUID serverId = UUID.fromString(created.getId());

    // 执行显式发现
    McpServerDiscoveryResponseDTO response = mcpServerService.discoverServer(created.getId(), "0");

    assertNotNull(response.getServer());
    assertNull(response.getOperation()); // Remote 无异步 operation
    assertEquals("AVAILABLE", response.getServer().getDiscoveryStatus());
    assertEquals("0", response.getServer().getDiscoveredVersion());
    assertEquals(2, response.getServer().getToolCount());

    List<McpTool> tools = repository.listTools(serverId);
    assertEquals(2, tools.size());
    McpTool tool1 =
        tools.stream().filter(t -> t.getSourceName().equals("Search-Files.v1")).findFirst().get();
    assertEquals("Search-Files.v1", tool1.getSourceName());
    assertEquals("mcp_remote_sync_search_files_v1", tool1.getModelName());
    assertTrue(tool1.isAvailable());
    assertEquals(0L, tool1.getSchemaRevision());

    // 远端变更：移除 read_file，更新 Search-Files.v1 schema
    fakeServer.clearTools();
    fakeServer.addTool(
        "Search-Files.v1",
        "Updated desc",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");

    McpServerDiscoveryResponseDTO response2 = mcpServerService.discoverServer(created.getId(), "0");
    assertEquals(1, response2.getServer().getToolCount()); // available count

    List<McpTool> toolsAfter = repository.listTools(serverId);
    assertEquals(2, toolsAfter.size()); // 1 active + 1 tombstoned

    McpTool updatedTool =
        toolsAfter.stream()
            .filter(t -> t.getSourceName().equals("Search-Files.v1"))
            .findFirst()
            .get();
    assertTrue(updatedTool.isAvailable());
    assertEquals(1L, updatedTool.getSchemaRevision()); // schema 改变 revision 递增

    McpTool tombstonedTool =
        toolsAfter.stream().filter(t -> t.getSourceName().equals("read_file")).findFirst().get();
    assertFalse(tombstonedTool.isAvailable()); // tombstone 标记为不可用
    assertEquals(0L, tombstonedTool.getSchemaRevision());
  }

  @Test
  public void updatesServerWithCasAndResetsDiscoveryStatus() {
    // 意图：验证更新配置时通过 CAS 推进 version 并将 discoveryStatus 重置为 UNVERIFIED
    fakeServer.addTool("ping", "Ping", "{}");

    String config1 =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("update_test");
    createDTO.setConfigJson(config1);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    mcpServerService.discoverServer(created.getId(), "0");

    McpServerDTO availableServer = mcpServerService.getServer(created.getId());
    assertEquals("AVAILABLE", availableServer.getDiscoveryStatus());
    assertEquals("0", availableServer.getVersion());

    // 1. 更新为新配置
    String config2 =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 8000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerUpdateDTO updateDTO = new McpServerUpdateDTO();
    updateDTO.setExpectedVersion("0");
    updateDTO.setConfigJson(config2);

    McpServerDTO updated = mcpServerService.updateServer(created.getId(), updateDTO);
    assertEquals("1", updated.getVersion());
    assertEquals(8000L, updated.getTimeoutMillis());
    assertEquals("UNVERIFIED", updated.getDiscoveryStatus()); // 状态重置

    // 2. CAS 冲突检查
    assertThrows(
        AiVersionConflictException.class,
        () -> mcpServerService.updateServer(created.getId(), updateDTO));
  }

  @Test
  public void blocksServerDeleteWhenReferencedByAgentDefinition() {
    // 意图：当任一 agent_definition.config.tools 引用工具的模型可见名时，Server 删除被 AiInUseException 拦截
    fakeServer.addTool("critical_tool", "Critical", "{}");

    String configJson =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("inuse_test");
    createDTO.setConfigJson(configJson);

    McpServerDTO created = mcpServerService.createServer(createDTO);
    mcpServerService.discoverServer(created.getId(), "0");

    UUID serverId = UUID.fromString(created.getId());
    McpTool tool = repository.listTools(serverId).get(0);
    String modelName = tool.getModelName();

    // 模拟存在 agent_definition.config.tools 引用了该模型可见名
    insertFakeAgentDefinitionReferencingTool(modelName);

    // 1. 删除 Server 被拦截
    assertThrows(AiInUseException.class, () -> mcpServerService.deleteServer(created.getId(), "0"));

    // 2. 清理 agent_definition 引用后，delete 成功，且级联删除工具行
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
    String configJson =
        """
        {
          "type": "remote",
          "url": "%s",
          "timeoutMillis": 5000
        }
        """
            .formatted(fakeServer.endpointUrl());

    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName("page_test");
    createDTO.setConfigJson(configJson);

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

  private void insertFakeAgentDefinitionReferencingTool(String modelName) {
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) values ('test_p_mcp', 'openai', '{}'::jsonb, '00000000-0000-0000-0000-000000000099'::uuid) on conflict do nothing");
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values ('test_p_mcp', 'm1', 'm1', '{}'::jsonb) on conflict do nothing");

    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values ('test_agent_mcp', 'test_p_mcp', 'm1', "
            + "cast('{\"tools\": [\""
            + modelName
            + "\"]}' as jsonb))");
  }
}
