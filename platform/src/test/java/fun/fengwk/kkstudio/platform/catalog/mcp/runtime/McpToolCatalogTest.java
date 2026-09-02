package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 动态 MCP 运行时工具目录测试。
 *
 * <p>验证从 Repository 现读 MCP 工具数据并映射为符合契约的 ToolContribution。
 */
class McpToolCatalogTest {

  private McpServerRepository repository;
  private McpToolClientFactory clientFactory;
  private McpToolCatalog catalog;

  @BeforeEach
  void setUp() {
    repository = mock(McpServerRepository.class);
    clientFactory = mock(McpToolClientFactory.class);
    catalog = new McpToolCatalog(repository, clientFactory);
  }

  @Test
  void listsSelectableToolsAcrossAllServers() {
    // 意图：验证从全部 Server 聚合工具列表并正确按 AgentToolId 排序
    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("github");
    server.setUrl("https://mcp.github.com");
    server.setTimeoutMillis(15000L);
    server.setVersion(2L);

    UUID tool1Id = UUID.randomUUID();
    McpTool tool1 = new McpTool();
    tool1.setId(tool1Id);
    tool1.setServerId(serverId);
    tool1.setSourceName("list_repos");
    tool1.setModelName("mcp_github_list_repos");
    tool1.setDescription("List repositories");
    tool1.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");

    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listTools(serverId)).thenReturn(List.of(tool1));

    List<ToolContribution> tools = catalog.selectableTools();
    assertEquals(1, tools.size());

    ToolContribution contribution = tools.get(0);
    assertEquals(McpStableIds.agentToolId(tool1Id), contribution.definition().id());
    assertEquals(ToolVisibility.SELECTABLE, contribution.definition().visibility());
    assertEquals("mcp_github_list_repos", contribution.definition().descriptor().name());
    assertEquals("2", contribution.definition().descriptor().version());
    assertEquals("List repositories", contribution.definition().descriptor().description());
    assertEquals("tool", contribution.definition().descriptor().rendererKey());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT, contribution.definition().descriptor().sideEffect());
    assertEquals(15000L, contribution.definition().descriptor().timeout().toMillis());
    assertEquals("platform.mcp", contribution.id().contributorId().value());
    assertEquals(McpStableIds.localName(tool1Id), contribution.id().localName());
  }

  @Test
  void findsToolByAgentToolId() {
    // 意图：验证按 AgentToolId 查找工具贡献
    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("brave");
    server.setUrl("https://brave.com/mcp");
    server.setTimeoutMillis(5000L);
    server.setVersion(0L);

    UUID toolId = UUID.randomUUID();
    McpTool tool = new McpTool();
    tool.setId(toolId);
    tool.setServerId(serverId);
    tool.setSourceName("web_search");
    tool.setModelName("mcp_brave_web_search");
    tool.setDescription("Search web");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");

    when(repository.getToolById(toolId)).thenReturn(Optional.of(tool));
    when(repository.getById(serverId)).thenReturn(Optional.of(server));

    AgentToolId agentToolId = McpStableIds.agentToolId(toolId);
    Optional<ToolContribution> result = catalog.findTool(agentToolId);
    assertTrue(result.isPresent());
    assertEquals(agentToolId, result.get().definition().id());
    assertEquals("mcp_brave_web_search", result.get().definition().descriptor().name());
  }

  @Test
  void returnsEmptyWhenToolOrServerNotFound() {
    // 意图：验证未知 AgentToolId 或非法前缀返回 empty
    AgentToolId unknownId = new AgentToolId("mcp.00000000000000000000000000000000");
    when(repository.getToolById(UUID.fromString("00000000-0000-0000-0000-000000000000")))
        .thenReturn(Optional.empty());

    assertFalse(catalog.findTool(unknownId).isPresent());
    assertFalse(catalog.findTool(new AgentToolId("other.invalid")).isPresent());
  }
}
