package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 动态 MCP 运行时工具目录测试。
 *
 * <p>验证从 Repository 现读 MCP 工具数据并映射为符合契约的 ToolContribution。
 */
class McpToolCatalogTest {

  private McpServerRepository repository;
  private McpToolCatalog catalog;

  @BeforeEach
  void setUp() {
    repository = mock(McpServerRepository.class);
    catalog = new McpToolCatalog(repository, mock(ExecutorService.class));
  }

  @Test
  void listsSelectableToolsAcrossAllServers() {
    // 意图：验证从全部 Server 聚合工具列表并正确按模型可见工具名排序
    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("github");
    server.setConnectionType(McpConnectionType.REMOTE);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setDiscoveredVersion(2L);
    server.setEnabled(true);
    server.setVersion(2L);
    server.setConnectionConfig("{\"url\":\"https://mcp.github.com\",\"headers\":{}}");
    server.setTimeoutMillis(15000L);

    UUID tool1Id = UUID.randomUUID();
    McpTool tool1 = new McpTool();
    tool1.setId(tool1Id);
    tool1.setServerId(serverId);
    tool1.setSourceName("list_repos");
    tool1.setModelName("mcp_github_list_repos");
    tool1.setDescription("List repositories");
    tool1.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    tool1.setAvailable(true);
    tool1.setSchemaRevision(2L);

    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listAvailableTools(serverId)).thenReturn(List.of(tool1));
    when(repository.getAvailableToolByModelName("mcp_github_list_repos"))
        .thenReturn(Optional.of(tool1));
    when(repository.getById(serverId)).thenReturn(Optional.of(server));

    List<ToolContribution> tools = catalog.selectableTools();
    assertEquals(1, tools.size());

    ToolContribution contribution = tools.get(0);
    assertEquals(ToolVisibility.SELECTABLE, contribution.definition().visibility());
    assertEquals("mcp_github_list_repos", contribution.definition().descriptor().name());
    assertEquals("List repositories", contribution.definition().descriptor().description());
    assertEquals("tool", contribution.definition().descriptor().rendererKey());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT, contribution.definition().descriptor().sideEffect());
    assertEquals(15000L, contribution.definition().descriptor().timeout().toMillis());
    assertEquals("platform.mcp", contribution.id().contributorId().value());
    assertEquals(McpStableIds.localName(tool1Id), contribution.id().localName());
    assertFalse(contribution.requirements().environmentRequired());
  }

  @Test
  void findsToolByModelName() {
    // 意图：验证按 mcp_tool.model_name 查找工具贡献
    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("brave");
    server.setConnectionType(McpConnectionType.REMOTE);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setDiscoveredVersion(0L);
    server.setEnabled(true);
    server.setVersion(0L);
    server.setConnectionConfig("{\"url\":\"https://brave.com/mcp\",\"headers\":{}}");
    server.setTimeoutMillis(5000L);

    UUID toolId = UUID.randomUUID();
    McpTool tool = new McpTool();
    tool.setId(toolId);
    tool.setServerId(serverId);
    tool.setSourceName("web_search");
    tool.setModelName("mcp_brave_web_search");
    tool.setDescription("Search web");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    tool.setAvailable(true);
    tool.setSchemaRevision(1L);

    when(repository.getAvailableToolByModelName("mcp_brave_web_search"))
        .thenReturn(Optional.of(tool));
    when(repository.getById(serverId)).thenReturn(Optional.of(server));

    Optional<ToolContribution> result = catalog.findTool("mcp_brave_web_search");
    assertTrue(result.isPresent());
    assertEquals("mcp_brave_web_search", result.get().definition().descriptor().name());
    assertEquals(McpStableIds.localName(toolId), result.get().id().localName());
  }

  @Test
  void localMcpToolRequiresEnvironmentId() {
    // 意图：验证 Local MCP 工具贡献必须附带 requiredEnvironmentId
    UUID serverId = UUID.randomUUID();
    UUID envUuid = UUID.randomUUID();
    EnvironmentId envId = EnvironmentId.of(envUuid);
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("local-dev");
    server.setConnectionType(McpConnectionType.LOCAL);
    server.setEnvironmentId(envUuid);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setDiscoveredVersion(1L);
    server.setEnabled(true);
    server.setVersion(1L);
    server.setConnectionConfig(
        "{\"command\":[\"node\",\"server.js\"],\"cwd\":\"/app\",\"env\":{}}");
    server.setTimeoutMillis(5000L);

    UUID toolId = UUID.randomUUID();
    McpTool tool = new McpTool();
    tool.setId(toolId);
    tool.setServerId(serverId);
    tool.setSourceName("local_tool");
    tool.setModelName("mcp_local_tool");
    tool.setDescription("Local tool description");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    tool.setAvailable(true);
    tool.setSchemaRevision(1L);

    when(repository.getAvailableToolByModelName("mcp_local_tool")).thenReturn(Optional.of(tool));
    when(repository.getById(serverId)).thenReturn(Optional.of(server));

    Optional<ToolContribution> result = catalog.findTool("mcp_local_tool");
    assertTrue(result.isPresent());
    assertTrue(result.get().requirements().environmentRequired());
    assertEquals(envId, result.get().requirements().requiredEnvironmentId());
  }

  @Test
  void returnsEmptyWhenToolOrServerNotFound() {
    // 意图：未知模型可见工具名、或工具存在但 Server 不可选择时返回 empty
    assertFalse(catalog.findTool("mcp_unknown_tool").isPresent());

    UUID serverId = UUID.randomUUID();
    McpServer disabledServer = new McpServer();
    disabledServer.setId(serverId);
    disabledServer.setName("disabled");
    disabledServer.setConnectionType(McpConnectionType.REMOTE);
    disabledServer.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    disabledServer.setDiscoveredVersion(1L);
    disabledServer.setEnabled(false);
    disabledServer.setVersion(1L);
    disabledServer.setConnectionConfig("{\"url\":\"https://mcp.example.com\",\"headers\":{}}");
    disabledServer.setTimeoutMillis(5000L);
    McpTool tool = new McpTool();
    tool.setId(UUID.randomUUID());
    tool.setServerId(serverId);
    tool.setSourceName("hidden");
    tool.setModelName("mcp_disabled_hidden");
    tool.setDescription("Hidden");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    tool.setAvailable(true);
    tool.setSchemaRevision(1L);
    when(repository.getAvailableToolByModelName("mcp_disabled_hidden"))
        .thenReturn(Optional.of(tool));
    when(repository.getById(serverId)).thenReturn(Optional.of(disabledServer));

    assertFalse(catalog.findTool("mcp_disabled_hidden").isPresent());
  }
}
