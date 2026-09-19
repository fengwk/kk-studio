package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 动态 MCP 运行时工具目录测试。
 *
 * <p>验证从 Repository 现读 MCP 工具数据并映射为符合契约的 {@link ToolContribution}：模型可见工具名就是 ContributionId 的
 * localName，且只有 enabled 的 AVAILABLE server 下的工具可被选择。
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
    // 意图：从全部 Server 聚合工具并按模型可见工具名排序，工具名即 ContributionId.localName
    McpServer server = server("github", McpDiscoveryStatus.AVAILABLE, true);
    McpTool tool = tool("mcp_github_list_repos", "github", "list_repos", "List repositories");

    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listTools("github")).thenReturn(List.of(tool));
    when(repository.getByName("github")).thenReturn(Optional.of(server));

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
    assertEquals("mcp-github-list-repos", contribution.id().localName());
    assertFalse(contribution.requirements().environmentRequired());
  }

  @Test
  void findsToolByModelVisibleName() {
    // 意图：按 mcp_tool.name 查找到可执行贡献，且不依赖任何隐藏 UUID
    McpServer server = server("brave", McpDiscoveryStatus.AVAILABLE, true);
    McpTool tool = tool("mcp_brave_search", "brave", "search", "Search");

    when(repository.getTool("mcp_brave_search")).thenReturn(Optional.of(tool));
    when(repository.getByName("brave")).thenReturn(Optional.of(server));

    ToolContribution contribution = catalog.findTool("mcp_brave_search").orElseThrow();
    assertEquals("mcp_brave_search", contribution.definition().descriptor().name());
    assertEquals("mcp-brave-search", contribution.id().localName());
    assertTrue(catalog.findTool("mcp_brave_missing").isEmpty());
  }

  @Test
  void excludesToolsOfDisabledOrUnverifiedServers() {
    // 意图：只有 enabled 且 AVAILABLE 的 server 下的工具进入运行时目录
    McpServer disabled = server("disabled", McpDiscoveryStatus.AVAILABLE, false);
    McpServer unverified = server("unverified", McpDiscoveryStatus.UNVERIFIED, true);
    McpServer failed = server("failed", McpDiscoveryStatus.FAILED, true);
    McpTool tool = tool("mcp_disabled_tool", "disabled", "tool", "Tool");

    when(repository.listAllServers()).thenReturn(List.of(disabled, unverified, failed));
    assertTrue(catalog.selectableTools().isEmpty());

    when(repository.getTool("mcp_disabled_tool")).thenReturn(Optional.of(tool));
    when(repository.getByName("disabled")).thenReturn(Optional.of(disabled));
    assertTrue(catalog.findTool("mcp_disabled_tool").isEmpty());
  }

  private static McpServer server(String name, McpDiscoveryStatus status, boolean enabled) {
    McpServer server = new McpServer();
    server.setName(name);
    server.setUrl("https://mcp.example.com/mcp");
    server.setHeaders(Map.of());
    server.setEnabled(enabled);
    server.setTimeoutMillis(15000L);
    server.setDiscoveryStatus(status);
    server.setVersion(1L);
    return server;
  }

  private static McpTool tool(
      String name, String serverName, String sourceName, String description) {
    McpTool tool = new McpTool();
    tool.setName(name);
    tool.setServerName(serverName);
    tool.setSourceName(sourceName);
    tool.setDescription(description);
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    return tool;
  }
}
