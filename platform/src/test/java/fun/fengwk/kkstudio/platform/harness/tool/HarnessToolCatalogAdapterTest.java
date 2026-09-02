package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.List;
import java.util.Optional;

/** 静态 HarnessCatalog 的 RuntimeToolCatalog 适配器测试。 */
class HarnessToolCatalogAdapterTest {

  @Test
  void delegatesSelectableToolsAndFindTool() {
    // 意图：验证适配器忠实转发 selectableTools 与 findTool 到 underlying HarnessCatalog
    HarnessCatalog mockCatalog = mock(HarnessCatalog.class);
    ToolContribution mockContribution = mock(ToolContribution.class);
    AgentToolId toolId = new AgentToolId("test.tool");

    when(mockCatalog.selectableTools()).thenReturn(List.of(mockContribution));
    when(mockCatalog.findTool(toolId)).thenReturn(Optional.of(mockContribution));

    HarnessToolCatalogAdapter adapter = new HarnessToolCatalogAdapter(mockCatalog);

    assertEquals(List.of(mockContribution), adapter.selectableTools());
    verify(mockCatalog).selectableTools();

    Optional<ToolContribution> found = adapter.findTool(toolId);
    assertTrue(found.isPresent());
    assertEquals(mockContribution, found.get());
    verify(mockCatalog).findTool(toolId);

    AgentToolId unknownId = new AgentToolId("unknown.tool");
    when(mockCatalog.findTool(unknownId)).thenReturn(Optional.empty());
    assertFalse(adapter.findTool(unknownId).isPresent());
  }

  @Test
  void nullChecks() {
    // 意图：验证构造器与参数空值防护
    assertThrows(NullPointerException.class, () -> new HarnessToolCatalogAdapter(null));
    HarnessToolCatalogAdapter adapter = new HarnessToolCatalogAdapter(mock(HarnessCatalog.class));
    assertThrows(NullPointerException.class, () -> adapter.findTool(null));
  }
}
