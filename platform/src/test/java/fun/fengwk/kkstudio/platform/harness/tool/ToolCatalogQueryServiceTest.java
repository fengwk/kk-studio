package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolCatalogQueryService} 单元测试： 验证对 {@link RuntimeToolCatalog} 的委托并正确映射为 {@link
 * ToolCatalogEntryDTO}。
 */
class ToolCatalogQueryServiceTest {

  @Test
  void listToolsMapsSelectableToolsToDto() {
    // 意图：验证 listTools 从 RuntimeToolCatalog 获取可选工具并忠实转换为 ToolCatalogEntryDTO
    ToolContribution t1 = dummyContribution("read", "read files");
    ToolContribution t2 = dummyContribution("search", "search web");

    RuntimeToolCatalog catalog = mock(RuntimeToolCatalog.class);
    when(catalog.selectableTools()).thenReturn(List.of(t1, t2));

    ToolCatalogQueryService service = new ToolCatalogQueryService(catalog);
    List<ToolCatalogEntryDTO> dtos = service.listTools();

    assertEquals(2, dtos.size());
    assertEquals("read", dtos.get(0).getName());
    assertEquals("read files", dtos.get(0).getDescription());

    assertEquals("search", dtos.get(1).getName());
    assertEquals("search web", dtos.get(1).getDescription());
  }

  @Test
  void nullCheckOnConstructor() {
    // 意图：验证构造器参数非空检查
    assertThrows(NullPointerException.class, () -> new ToolCatalogQueryService(null));
  }

  private static ToolContribution dummyContribution(String modelName, String description) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            modelName,
            description,
            "renderer",
            new InputSchema("{}", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
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
