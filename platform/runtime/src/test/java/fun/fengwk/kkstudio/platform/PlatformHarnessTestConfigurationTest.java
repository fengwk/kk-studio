package fun.fengwk.kkstudio.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;

import java.util.List;
import java.util.concurrent.ExecutorService;

class PlatformHarnessTestConfigurationTest {

  @Test
  void mcpFactsAreFreshForEachLeaseAndFrozenUntilRelease() {
    // 测试组合根必须看到启动后新增的 MCP 行，同时保持一次规划租用内的目录不漂移。
    McpServerRepository repository = mock(McpServerRepository.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repository, mock(ExecutorService.class));
    RuntimeToolCatalogSource source =
        new PlatformHarnessTestConfiguration()
            .runtimeToolCatalogSource(HarnessCatalog.from(List.of()), mcpCatalog);
    try (RuntimeToolCatalogSource.Lease before = source.acquire()) {
      assertTrue(before.catalog().selectableTools().isEmpty());
    }

    McpServer server = new McpServer();
    server.setName("catalog");
    server.setEnabled(true);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setTimeoutMillis(5000L);
    McpTool tool = new McpTool();
    tool.setName("mcp_catalog_critical");
    tool.setServerName(server.getName());
    tool.setSourceName("critical");
    tool.setDescription("Critical tool");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listTools(server.getName())).thenReturn(List.of(tool));
    try (RuntimeToolCatalogSource.Lease frozen = source.acquire()) {
      assertTrue(frozen.catalog().findTool(tool.getName()).isPresent());
      when(repository.listTools(server.getName())).thenReturn(List.of());
      assertTrue(frozen.catalog().findTool(tool.getName()).isPresent());
      try (RuntimeToolCatalogSource.Lease current = source.acquire()) {
        assertFalse(current.catalog().findTool(tool.getName()).isPresent());
      }
    }
  }
}
