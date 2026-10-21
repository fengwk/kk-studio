package fun.fengwk.kkstudio.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;

import java.util.List;

class PlatformHarnessTestConfigurationTest {

  @Test
  void mcpFactsAreFreshForEachLeaseAndFrozenUntilRelease() {
    // 测试组合根必须看到启动后新增的 MCP 行，同时保持一次规划租用内的目录不漂移。
    McpServerRepository repository = mock(McpServerRepository.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repository);
    RuntimeToolCatalogSource source =
        new PlatformHarnessTestConfiguration()
            .runtimeToolCatalogSource(HarnessCatalog.from(List.of()), mcpCatalog);
    try (RuntimeToolCatalogSource.Lease before = source.acquire()) {
      assertTrue(before.catalog().selectableTools().isEmpty());
    }

    McpServer server =
        McpServer.builder()
            .name("catalog")
            .enabled(true)
            .discoveryStatus(McpDiscoveryStatus.AVAILABLE)
            .timeoutMillis(5000L)
            .build();
    McpTool tool =
        McpTool.builder()
            .name("mcp_catalog_critical")
            .serverName(server.name())
            .sourceName("critical")
            .description("Critical tool")
            .inputSchemaJson(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")
            .build();
    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listTools(server.name())).thenReturn(List.of(tool));
    try (RuntimeToolCatalogSource.Lease frozen = source.acquire()) {
      assertTrue(frozen.catalog().findTool(tool.name()).isPresent());
      when(repository.listTools(server.name())).thenReturn(List.of());
      assertTrue(frozen.catalog().findTool(tool.name()).isPresent());
      try (RuntimeToolCatalogSource.Lease current = source.acquire()) {
        assertFalse(current.catalog().findTool(tool.name()).isPresent());
      }
    }
  }
}
