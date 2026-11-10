package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.contributor.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.dto.ToolCatalogEntryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ToolProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ToolProviderKind;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.testing.TestCatalogSources;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolCatalogQueryService} 单元测试： 验证对 {@link RuntimeToolCatalog} 的委托并正确映射为 {@link
 * ToolCatalogEntryDTO}，含四类 provider（BUILTIN/APP/PLUGIN/MCP）。
 */
class ToolCatalogQueryServiceTest {

  @Test
  void listToolsMapsSelectableToolsToDto() {
    // 意图：验证 listTools 从 RuntimeToolCatalog 获取可选工具并忠实转换为 ToolCatalogEntryDTO
    ToolContribution t1 = dummyContribution("read", "read files");
    ToolContribution t2 = dummyContribution("search", "search web");
    SelectableTool s1 =
        new SelectableTool(
            t1, new ToolProviderDTO(ToolProviderKind.BUILTIN, "builtin", "Built-in"));
    SelectableTool s2 =
        new SelectableTool(t2, new ToolProviderDTO(ToolProviderKind.MCP, "github", "GitHub"));

    RuntimeToolCatalog catalog = mock(RuntimeToolCatalog.class);
    when(catalog.selectableTools()).thenReturn(List.of(s1, s2));

    ToolCatalogQueryService service =
        new ToolCatalogQueryService(TestCatalogSources.singleLease(catalog));
    List<ToolCatalogEntryDTO> dtos = service.listTools();

    assertEquals(2, dtos.size());
    assertEquals("read", dtos.get(0).getName());
    assertEquals("read files", dtos.get(0).getDescription());
    assertEquals(EnvironmentSupport.NONE, dtos.get(0).getEnvironmentSupport());
    assertEquals(
        new ToolProviderDTO(ToolProviderKind.BUILTIN, "builtin", "Built-in"),
        dtos.get(0).getProvider());

    assertEquals("search", dtos.get(1).getName());
    assertEquals("search web", dtos.get(1).getDescription());
    assertEquals(
        new ToolProviderDTO(ToolProviderKind.MCP, "github", "GitHub"), dtos.get(1).getProvider());
  }

  /** 选择面 provider 按目录冻结的 contributor 来源填写：BUILTIN/APP/PLUGIN 来自 Harness 目录，MCP 来自服务名。 */
  @Test
  void listToolsExposesAllFourProviderKindsFromTheFrozenCatalog() {
    // 意图：四类 provider 在真实冻结目录（生产 FrozenRuntimeToolCatalog）上各出现一次，DTO 忠实透传
    HarnessCatalog harnessCatalog =
        HarnessCatalog.from(
            List.of(builtinContributor(), hostContributor("project.app"), pluginContributor()));
    McpToolCatalog mcpCatalog = mcpCatalogWithOneSelectableTool();

    TestRuntimeToolCatalogSource source =
        TestRuntimeToolCatalogSource.of(
            harnessCatalog,
            Map.of(
                new ContributorId("project.app"),
                new ToolProviderDTO(ToolProviderKind.APP, "project.app", "Project"),
                new ContributorId("demo.plugin"),
                new ToolProviderDTO(ToolProviderKind.PLUGIN, "demo.plugin", "Demo Plugin")),
            mcpCatalog);
    ToolCatalogQueryService service = new ToolCatalogQueryService(source);

    Map<String, ToolCatalogEntryDTO> byName = new LinkedHashMap<>();
    for (ToolCatalogEntryDTO dto : service.listTools()) {
      byName.put(dto.getName(), dto);
    }

    assertEquals(
        new ToolProviderDTO(ToolProviderKind.BUILTIN, "builtin", BuiltinHarnessContributor.NAME),
        byName.get("read").getProvider());
    assertEquals(
        new ToolProviderDTO(ToolProviderKind.APP, "project.app", "Project"),
        byName.get("app_tool").getProvider());
    assertEquals(
        new ToolProviderDTO(ToolProviderKind.PLUGIN, "demo.plugin", "Demo Plugin"),
        byName.get("plugin_tool").getProvider());
    assertEquals(
        new ToolProviderDTO(ToolProviderKind.MCP, "github", "github"),
        byName.get("mcp_github_list_repos").getProvider());
    assertNull(byName.get("task"), "internal tools never appear on the selectable surface");
  }

  @Test
  void nullCheckOnConstructor() {
    // 意图：验证构造器参数非空检查
    assertThrows(NullPointerException.class, () -> new ToolCatalogQueryService(null));
  }

  private static HarnessContributor builtinContributor() {
    Tool task = mock(Tool.class);
    when(task.descriptor()).thenReturn(descriptor("task", "delegate"));
    when(task.requirements()).thenReturn(ToolRequirements.none());
    // BuiltinHarnessContributor 要求 5 个文件工具齐全；这里全部用桩工具，只关心 provider 判定。
    return new BuiltinHarnessContributor(
        List.of(
            selectable("read", "read files"),
            selectable("write", "write files"),
            selectable("edit", "edit files"),
            selectable("grep", "grep files"),
            selectable("find", "find files")),
        task);
  }

  private static HarnessContributor hostContributor(String id) {
    return HarnessContributor.of(
        new ContributorDescriptor(new ContributorId(id), "Project", "1", Set.of()),
        registrar ->
            registrar.registerTool(
                "app.tool", selectable("app_tool", "app tool"), ToolVisibility.SELECTABLE, 0));
  }

  private static HarnessContributor pluginContributor() {
    return HarnessContributor.of(
        new ContributorDescriptor(
            new ContributorId("demo.plugin"), "plugin contrib", "1", Set.of()),
        registrar ->
            registrar.registerTool(
                "plugin.tool",
                selectable("plugin_tool", "plugin tool"),
                ToolVisibility.SELECTABLE,
                0));
  }

  private static McpToolCatalog mcpCatalogWithOneSelectableTool() {
    McpServerRepository repository = mock(McpServerRepository.class);
    McpServer server =
        McpServer.builder()
            .name("github")
            .url("https://mcp.example.com/mcp")
            .headers(Map.of())
            .enabled(true)
            .timeoutMillis(15000L)
            .discoveryStatus(McpDiscoveryStatus.AVAILABLE)
            .version(1L)
            .build();
    McpTool tool =
        McpTool.builder()
            .name("mcp_github_list_repos")
            .serverName("github")
            .sourceName("list_repos")
            .description("List repositories")
            .inputSchemaJson(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}")
            .build();
    when(repository.listAllServers()).thenReturn(List.of(server));
    when(repository.listTools("github")).thenReturn(List.of(tool));
    return new McpToolCatalog(repository);
  }

  private static Tool selectable(String name, String description) {
    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor(name, description));
    when(tool.requirements()).thenReturn(ToolRequirements.none());
    return tool;
  }

  private static ToolDescriptor descriptor(String name, String description) {
    return new ToolDescriptor(
        name,
        description,
        "renderer",
        new InputSchema("{}", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static ToolContribution dummyContribution(String modelName, String description) {
    ToolDescriptor descriptor = descriptor(modelName, description);
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
    Tool executable = mock(Tool.class);
    when(executable.descriptor()).thenReturn(descriptor);
    when(executable.requirements()).thenReturn(ToolRequirements.none());
    return new ToolContribution(
        new ContributionId(new ContributorId("contributor"), "local-" + modelName),
        definition,
        executable,
        ToolRequirements.none(),
        0);
  }
}
