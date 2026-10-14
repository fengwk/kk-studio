package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
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
 * localName；可选面（{@code selectableTools}）只包含 enabled 的 AVAILABLE server 下的工具，而查找面（{@code findTool}）
 * 只要求持久化行存在，不按 server 可用性过滤。
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

    List<ToolContribution> tools = catalog.selectableTools();
    assertEquals(1, tools.size());

    ToolContribution contribution = tools.get(0);
    assertEquals(ToolVisibility.SELECTABLE, contribution.definition().visibility());
    assertEquals("mcp_github_list_repos", contribution.definition().descriptor().name());
    assertEquals("List repositories", contribution.definition().descriptor().description());
    assertEquals("tool", contribution.definition().descriptor().rendererKey());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT, contribution.definition().descriptor().sideEffect());
    assertEquals(15000L, contribution.definition().descriptor().defaultTimeout().toMillis());
    assertEquals("platform.mcp", contribution.id().contributorId().value());
    assertEquals("mcp-github-list-repos", contribution.id().localName());
    assertEquals(EnvironmentSupport.NONE, contribution.requirements().environmentSupport());
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
  }

  @Test
  void selectableToolsExcludesDisabledOrNonAvailableServers() {
    // 意图：可选面（UI/配置选择）只包含 enabled 且 AVAILABLE 的 server 下的工具；三种不可用形态都不进入该面
    McpServer disabled = server("disabled", McpDiscoveryStatus.AVAILABLE, false);
    McpServer unverified = server("unverified", McpDiscoveryStatus.UNVERIFIED, true);
    McpServer failed = server("failed", McpDiscoveryStatus.FAILED, true);

    when(repository.listAllServers()).thenReturn(List.of(disabled, unverified, failed));
    assertTrue(catalog.selectableTools().isEmpty());
  }

  @Test
  void findToolResolvesPersistedDefinitionOfDisabledUnverifiedAndFailedServers() {
    // 意图：规划/查找面只要求持久化定义存在。server 被禁用、未验证或发现失败都不再让已持久化工具变成 tool-not-found，
    // 可用性由调用期的 RemoteMcpExecutableTool 按同一 DB 行 fail closed。
    for (McpDiscoveryStatus status : McpDiscoveryStatus.values()) {
      for (boolean enabled : List.of(true, false)) {
        String toolName = "mcp_srv_tool";
        McpServer server = server("srv", status, enabled);
        McpTool tool = tool(toolName, "srv", "tool", "Tool");
        when(repository.getTool(toolName)).thenReturn(Optional.of(tool));
        when(repository.getByName("srv")).thenReturn(Optional.of(server));

        ToolContribution contribution =
            catalog
                .findTool(toolName)
                .orElseThrow(
                    () ->
                        new AssertionError(
                            "persisted tool must resolve for status="
                                + status
                                + " enabled="
                                + enabled));

        assertEquals(toolName, contribution.definition().descriptor().name());
        assertEquals("mcp-srv-tool", contribution.id().localName());
        assertEquals(15000L, contribution.definition().descriptor().defaultTimeout().toMillis());
      }
    }

    // 同一 server 的可用性不影响兄弟工具：两种状态都能各自解析出精确定义，且与可选面无耦合。
    McpServer available = server("srv", McpDiscoveryStatus.AVAILABLE, true);
    McpTool sibling = tool("mcp_srv_sibling", "srv", "sibling", "Sibling");
    when(repository.getByName("srv")).thenReturn(Optional.of(available));
    when(repository.getTool("mcp_srv_sibling")).thenReturn(Optional.of(sibling));
    assertEquals(
        "mcp_srv_sibling",
        catalog.findTool("mcp_srv_sibling").orElseThrow().definition().descriptor().name());

    // 定义与可用性解耦：同一工具行在可用与不可用 server 下解析出的贡献身份与冻结定义完全一致，
    // 因此 planning 期冻结的绑定不会因 server 后来被禁用或发现失败而在调用期 preflight 漂移，
    // 且定义中的 timeout 始终取自 server 行，不因可用性被改写。
    McpTool defined = tool("mcp_srv_defined", "srv", "defined", "Defined");
    when(repository.getTool("mcp_srv_defined")).thenReturn(Optional.of(defined));
    when(repository.getByName("srv"))
        .thenReturn(Optional.of(server("srv", McpDiscoveryStatus.AVAILABLE, true)));
    ToolContribution availableFace = catalog.findTool("mcp_srv_defined").orElseThrow();
    when(repository.getByName("srv"))
        .thenReturn(Optional.of(server("srv", McpDiscoveryStatus.FAILED, false)));
    ToolContribution unavailableFace = catalog.findTool("mcp_srv_defined").orElseThrow();
    assertEquals(availableFace.id(), unavailableFace.id());
    assertEquals(availableFace.definition(), unavailableFace.definition());
    assertEquals(15000L, unavailableFace.definition().descriptor().defaultTimeout().toMillis());
  }

  @Test
  void findToolStaysEmptyForMissingToolOrServerRow() {
    // 意图：查找面仍然 fail closed：工具行不存在，或工具行的 server 行不存在时一律返回空，绝不合成定义
    when(repository.getTool("mcp_missing_tool")).thenReturn(Optional.empty());
    assertTrue(catalog.findTool("mcp_missing_tool").isEmpty());

    McpTool orphan = tool("mcp_orphan_tool", "ghost", "tool", "Tool");
    when(repository.getTool("mcp_orphan_tool")).thenReturn(Optional.of(orphan));
    when(repository.getByName("ghost")).thenReturn(Optional.empty());
    assertTrue(catalog.findTool("mcp_orphan_tool").isEmpty());
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
