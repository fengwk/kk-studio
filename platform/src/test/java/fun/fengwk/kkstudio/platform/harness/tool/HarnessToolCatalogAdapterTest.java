package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;

import java.util.List;
import java.util.Optional;

/** 静态 HarnessCatalog 的 RuntimeToolCatalog 适配器测试。 */
class HarnessToolCatalogAdapterTest {

  @Test
  void delegatesSelectableToolsAndFindTool() {
    // 意图：验证适配器忠实转发 selectableTools 与两种 findTool 到 underlying HarnessCatalog
    HarnessCatalog mockCatalog = mock(HarnessCatalog.class);
    ToolContribution mockContribution = mock(ToolContribution.class);
    String toolName = "test_tool";
    ContributionId contributionId =
        new ContributionId(new ContributorId("test.contributor"), "test-tool");

    when(mockCatalog.selectableTools()).thenReturn(List.of(mockContribution));
    when(mockCatalog.findTool(toolName)).thenReturn(Optional.of(mockContribution));
    when(mockCatalog.findTool(contributionId)).thenReturn(Optional.of(mockContribution));

    HarnessToolCatalogAdapter adapter = new HarnessToolCatalogAdapter(mockCatalog);

    assertEquals(List.of(mockContribution), adapter.selectableTools());
    verify(mockCatalog).selectableTools();

    Optional<ToolContribution> found = adapter.findTool(toolName);
    assertTrue(found.isPresent());
    assertEquals(mockContribution, found.get());
    verify(mockCatalog).findTool(toolName);

    assertEquals(Optional.of(mockContribution), adapter.findTool(contributionId));
    verify(mockCatalog).findTool(contributionId);

    when(mockCatalog.findTool("unknown_tool")).thenReturn(Optional.empty());
    assertFalse(adapter.findTool("unknown_tool").isPresent());
  }

  @Test
  void nullChecks() {
    // 意图：验证构造器与参数空值防护
    assertThrows(NullPointerException.class, () -> new HarnessToolCatalogAdapter(null));
    HarnessToolCatalogAdapter adapter = new HarnessToolCatalogAdapter(mock(HarnessCatalog.class));
    assertThrows(NullPointerException.class, () -> adapter.findTool((String) null));
    assertThrows(NullPointerException.class, () -> adapter.findTool((ContributionId) null));
  }
}
