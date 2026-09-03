package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link CompositeRuntimeToolCatalog} 的完整单元测试： 验证聚合顺序、查找、空值检查、重复源实例拒绝、AgentToolId/模型名称冲突
 * fail-closed、未知 ID 遇到无关冲突同样 fail-closed、 静态 internal 工具回退与无缓存动态性。
 */
class CompositeRuntimeToolCatalogTest {

  @Test
  void selectableToolsPreservesDeterministicDelegateOrder() {
    // 意图：验证 selectableTools 严格按照构造时传入的 delegate 顺序聚合工具（例如静态优先、动态在后）
    ToolContribution t1 = dummyContribution("static.t1", "tool_one", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dynamic.t2", "tool_two", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    List<ToolContribution> tools = composite.selectableTools();

    assertEquals(2, tools.size());
    assertEquals(t1, tools.get(0));
    assertEquals(t2, tools.get(1));
  }

  @Test
  void findToolFindsSelectableToolsAcrossDelegates() {
    // 意图：验证 findTool 能跨委托目录按 AgentToolId 正确查找到可选工具
    ToolContribution t1 = dummyContribution("tool.1", "t1", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("tool.2", "t2", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));

    Optional<ToolContribution> found1 = composite.findTool(new AgentToolId("tool.1"));
    assertTrue(found1.isPresent());
    assertEquals(t1, found1.get());

    Optional<ToolContribution> found2 = composite.findTool(new AgentToolId("tool.2"));
    assertTrue(found2.isPresent());
    assertEquals(t2, found2.get());

    Optional<ToolContribution> missing = composite.findTool(new AgentToolId("tool.unknown"));
    assertFalse(missing.isPresent());
  }

  @Test
  void constructorRejectsDuplicateCatalogSourceInstances() {
    // 意图：验证构造器拒绝传入相同的 catalog 实例
    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> new CompositeRuntimeToolCatalog(List.of(d1, d1)));
    assertEquals("duplicate runtime tool catalog source instance", error.getMessage());
  }

  @Test
  void constructorNullChecks() {
    // 意图：验证构造器及参数的空值防护
    assertThrows(
        NullPointerException.class,
        () -> new CompositeRuntimeToolCatalog((List<RuntimeToolCatalog>) null));
    List<RuntimeToolCatalog> listWithNull = new ArrayList<>();
    listWithNull.add(mock(RuntimeToolCatalog.class));
    listWithNull.add(null);
    assertThrows(NullPointerException.class, () -> new CompositeRuntimeToolCatalog(listWithNull));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of());
    assertThrows(NullPointerException.class, () -> composite.findTool(null));
  }

  @Test
  void selectableToolsFailsClosedOnDuplicateAgentToolIdAcrossSources() {
    // 意图：验证跨源存在重复 AgentToolId 时，selectableTools 立即抛出异常 fail-closed
    ToolContribution t1 = dummyContribution("dup.id", "tool_a", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dup.id", "tool_b", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, composite::selectableTools);
    assertTrue(error.getMessage().contains("duplicate AgentToolId"));
    assertTrue(error.getMessage().contains("dup.id"));
  }

  @Test
  void selectableToolsFailsClosedOnDuplicateModelNameAcrossSources() {
    // 意图：验证跨源存在重复模型可见名称时，selectableTools 立即抛出异常 fail-closed
    ToolContribution t1 = dummyContribution("id.1", "dup_name", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("id.2", "dup_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, composite::selectableTools);
    assertTrue(error.getMessage().contains("duplicate tool model name"));
    assertTrue(error.getMessage().contains("dup_name"));
  }

  @Test
  void findToolFailsClosedOnDuplicateAgentToolIdAcrossSources() {
    // 意图：验证通过 findTool 查找存在重复 AgentToolId 的工具时立即 fail-closed
    ToolContribution t1 = dummyContribution("dup.id", "tool_a", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dup.id", "tool_b", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> composite.findTool(new AgentToolId("dup.id")));
    assertTrue(error.getMessage().contains("duplicate AgentToolId"));
  }

  @Test
  void findToolFailsClosedOnDuplicateModelNameAcrossSources() {
    // 意图：验证通过 findTool 查找工具时，若存在重复的模型可见名称，立即 fail-closed 抛出异常
    ToolContribution t1 = dummyContribution("id.1", "dup_name", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("id.2", "dup_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> composite.findTool(new AgentToolId("id.1")));
    assertTrue(error.getMessage().contains("duplicate tool model name"));
  }

  @Test
  void findToolFailsClosedForUnknownIdWhenUnrelatedConflictExists() {
    // 意图：验证当 catalog 内部存在无关工具的模型名称或 AgentToolId 冲突时，即使查找未知 ID 也必须校验快照并 fail-closed
    ToolContribution t1 = dummyContribution("conflict.1", "dup_model", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("conflict.2", "dup_model", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    AgentToolId unrelatedId = new AgentToolId("completely.unrelated.id");

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool(unrelatedId));
    assertTrue(error.getMessage().contains("duplicate tool model name"));
  }

  @Test
  void findToolFailsClosedForUnknownIdWhenUnrelatedAgentToolIdConflictExists() {
    // 意图：验证当存在无关的 AgentToolId 冲突时，查找未知 ID 同样执行完整快照校验并 fail-closed
    ToolContribution t1 =
        dummyContribution("conflict.same-id", "tool_one", ToolVisibility.SELECTABLE);
    ToolContribution t2 =
        dummyContribution("conflict.same-id", "tool_two", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    AgentToolId unrelatedId = new AgentToolId("other.id");

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool(unrelatedId));
    assertTrue(error.getMessage().contains("duplicate AgentToolId"));
  }

  @Test
  void findToolFallsBackToDelegateForStaticInternalTools() {
    // 意图：验证不在 selectableTools 列表中的静态 INTERNAL 工具（如 load_skill/task）能通过 delegate.findTool 正常回退查找
    ToolContribution internal =
        dummyContribution("builtin.load-skill", "load_skill", ToolVisibility.INTERNAL);
    ToolContribution selectable =
        dummyContribution("host.search", "search", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog staticCatalog = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog dynamicCatalog = mock(RuntimeToolCatalog.class);

    // internal 工具不在 selectableTools 中
    when(staticCatalog.selectableTools()).thenReturn(List.of());
    when(staticCatalog.findTool(new AgentToolId("builtin.load-skill")))
        .thenReturn(Optional.of(internal));

    when(dynamicCatalog.selectableTools()).thenReturn(List.of(selectable));
    when(dynamicCatalog.findTool(new AgentToolId("builtin.load-skill")))
        .thenReturn(Optional.empty());

    CompositeRuntimeToolCatalog composite =
        new CompositeRuntimeToolCatalog(List.of(staticCatalog, dynamicCatalog));

    // selectableTools 只包含 selectable 工具
    assertEquals(List.of(selectable), composite.selectableTools());

    // findTool 能成功查找到 internal 工具
    Optional<ToolContribution> found = composite.findTool(new AgentToolId("builtin.load-skill"));
    assertTrue(found.isPresent());
    assertEquals(internal, found.get());
  }

  @Test
  void findToolFailsClosedWhenInternalToolConflictsWithSelectableToolModelName() {
    // 意图：验证当静态 INTERNAL 工具的模型可见名称与某个可选工具的模型名称冲突时，findTool 抛出异常 fail-closed
    ToolContribution internal =
        dummyContribution("builtin.internal", "same_name", ToolVisibility.INTERNAL);
    ToolContribution selectable =
        dummyContribution("mcp.tool", "same_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of());
    when(d1.findTool(new AgentToolId("builtin.internal"))).thenReturn(Optional.of(internal));

    when(d2.selectableTools()).thenReturn(List.of(selectable));
    when(d2.findTool(new AgentToolId("builtin.internal"))).thenReturn(Optional.empty());

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> composite.findTool(new AgentToolId("builtin.internal")));
    assertTrue(error.getMessage().contains("duplicate tool model name"));
  }

  @Test
  void findToolFailsClosedWhenDuplicateDelegatesMatchInternalToolId() {
    // 意图：验证当两个 delegate 均匹配同一个非可选 internal 工具 ID 时，严禁静默选择其一，必须 fail-closed
    ToolContribution internal1 =
        dummyContribution("dup.internal", "name_one", ToolVisibility.INTERNAL);
    ToolContribution internal2 =
        dummyContribution("dup.internal", "name_two", ToolVisibility.INTERNAL);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of());
    when(d2.selectableTools()).thenReturn(List.of());

    AgentToolId id = new AgentToolId("dup.internal");
    when(d1.findTool(id)).thenReturn(Optional.of(internal1));
    when(d2.findTool(id)).thenReturn(Optional.of(internal2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool(id));
    assertTrue(error.getMessage().contains("duplicate AgentToolId across catalogs"));
  }

  @Test
  void queriesDelegatesDynamicallyWithoutCaching() {
    // 意图：验证复合目录每次调用都现读 delegate，不进行任何缓存
    ToolContribution t1 = dummyContribution("id.1", "name1", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("id.2", "name2", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog delegate = mock(RuntimeToolCatalog.class);
    when(delegate.selectableTools()).thenReturn(List.of(t1)).thenReturn(List.of(t1, t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(delegate));

    assertEquals(1, composite.selectableTools().size());
    assertEquals(2, composite.selectableTools().size());
  }

  private static ToolContribution dummyContribution(
      String toolIdValue, String modelName, ToolVisibility visibility) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            modelName,
            "1.0",
            "description for " + modelName,
            "renderer",
            new InputSchema("{}", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    AgentToolDefinition definition =
        new AgentToolDefinition(new AgentToolId(toolIdValue), descriptor, visibility);
    Tool executable = mock(Tool.class);
    when(executable.descriptor()).thenReturn(descriptor);
    when(executable.requirements()).thenReturn(ToolRequirements.none());
    return new ToolContribution(
        new ContributionId(
            new ContributorId("test-contributor"), "local-" + modelName.replace('_', '-')),
        definition,
        executable,
        ToolRequirements.none(),
        0);
  }
}
