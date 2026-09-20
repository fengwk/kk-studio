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
 * {@link CompositeRuntimeToolCatalog} 的完整单元测试： 验证聚合顺序、按模型可见 name 查找、空值检查、重复源实例拒绝、同名冲突
 * fail-closed（无论 ContributionId 是否一致）、未知名称遇到无关冲突同样 fail-closed、 静态 internal 工具回退与无缓存动态性。
 */
class CompositeRuntimeToolCatalogTest {

  @Test
  void selectableToolsPreservesDeterministicDelegateOrder() {
    // 意图：验证 selectableTools 严格按照构造时传入的 delegate 顺序聚合工具（例如静态优先、动态在后）
    ToolContribution t1 = dummyContribution("tool_one", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("tool_two", ToolVisibility.SELECTABLE);

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
    // 意图：验证 findTool 跨委托目录按模型可见 tool name 精确查找到可选工具
    ToolContribution t1 = dummyContribution("t1", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("t2", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));

    Optional<ToolContribution> found1 = composite.findTool("t1");
    assertTrue(found1.isPresent());
    assertEquals(t1, found1.get());

    Optional<ToolContribution> found2 = composite.findTool("t2");
    assertTrue(found2.isPresent());
    assertEquals(t2, found2.get());

    assertFalse(composite.findTool("tool_unknown").isPresent());
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
  void selectableToolsFailsClosedOnDuplicateNameAcrossSources() {
    // 意图：验证跨源存在重复模型可见 name 时，selectableTools 立即 fail-closed 抛出异常
    ToolContribution t1 = dummyContribution("dup_name", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dup_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, composite::selectableTools);
    assertEquals("duplicate tool name across catalogs: dup_name", error.getMessage());
  }

  @Test
  void findToolFailsClosedOnDuplicateNameAcrossSources() {
    // 意图：验证通过 findTool 查找工具时，若存在重复的模型可见 name，立即 fail-closed 抛出异常
    ToolContribution t1 = dummyContribution("dup_name", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dup_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool("dup_name"));
    assertEquals("duplicate tool name across catalogs: dup_name", error.getMessage());
  }

  @Test
  void findToolFailsClosedForUnknownNameWhenUnrelatedConflictExists() {
    // 意图：验证 catalog 内部存在无关工具的同名冲突时，即使查找未知名称也必须先校验快照并 fail-closed
    ToolContribution t1 = dummyContribution("dup_model", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("dup_model", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of(t1));
    when(d2.selectableTools()).thenReturn(List.of(t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool("unrelated_name"));
    assertEquals("duplicate tool name across catalogs: dup_model", error.getMessage());
  }

  @Test
  void findToolFallsBackToDelegateForStaticInternalTools() {
    // 意图：不在 selectableTools 列表中的静态 INTERNAL 工具（如 task）能通过 delegate.findTool 正常回退查找
    ToolContribution internal = dummyContribution("task", ToolVisibility.INTERNAL);
    ToolContribution selectable = dummyContribution("search", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog staticCatalog = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog dynamicCatalog = mock(RuntimeToolCatalog.class);

    // internal 工具不在 selectableTools 中
    when(staticCatalog.selectableTools()).thenReturn(List.of());
    when(staticCatalog.findTool("task")).thenReturn(Optional.of(internal));

    when(dynamicCatalog.selectableTools()).thenReturn(List.of(selectable));
    when(dynamicCatalog.findTool("task")).thenReturn(Optional.empty());

    CompositeRuntimeToolCatalog composite =
        new CompositeRuntimeToolCatalog(List.of(staticCatalog, dynamicCatalog));

    // selectableTools 只包含 selectable 工具
    assertEquals(List.of(selectable), composite.selectableTools());

    // findTool 能成功查找到 internal 工具
    Optional<ToolContribution> found = composite.findTool("task");
    assertTrue(found.isPresent());
    assertEquals(internal, found.get());
  }

  @Test
  void findToolFailsClosedWhenInternalToolConflictsWithSelectableToolName() {
    // 意图：静态 INTERNAL 工具的模型可见 name 与另一个源的可选工具同名时，findTool 必须 fail-closed
    ToolContribution internal = dummyContribution("same_name", ToolVisibility.INTERNAL);
    ToolContribution selectable = dummyContribution("same_name", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of());
    when(d1.findTool("same_name")).thenReturn(Optional.of(internal));

    when(d2.selectableTools()).thenReturn(List.of(selectable));
    when(d2.findTool("same_name")).thenReturn(Optional.empty());

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool("same_name"));
    assertEquals("duplicate tool name across catalogs: same_name", error.getMessage());
  }

  @Test
  void findToolFailsClosedWhenTwoDelegatesMatchTheSameInternalToolName() {
    // 意图：非 selectable 工具被两个 delegate 同时匹配时，同名即冲突，即使 ContributionId 不同也必须 fail-closed
    ToolContribution internal1 = dummyContribution("dup_internal", ToolVisibility.INTERNAL);
    ToolContribution internal2 =
        duplicateOf(internal1, new ContributionId(new ContributorId("other"), "dup-internal"));

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of());
    when(d2.selectableTools()).thenReturn(List.of());

    when(d1.findTool("dup_internal")).thenReturn(Optional.of(internal1));
    when(d2.findTool("dup_internal")).thenReturn(Optional.of(internal2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool("dup_internal"));
    assertEquals("duplicate tool name across catalogs: dup_internal", error.getMessage());
  }

  @Test
  void findToolFailsClosedWhenTwoDelegatesMatchSameInternalToolWithEqualContributionId() {
    // 意图：两个 delegate 返回同名且相同 ContributionId 的贡献也属于重复来源，禁止静默接受
    ToolContribution internal = dummyContribution("dup_same_id", ToolVisibility.INTERNAL);

    RuntimeToolCatalog d1 = mock(RuntimeToolCatalog.class);
    RuntimeToolCatalog d2 = mock(RuntimeToolCatalog.class);

    when(d1.selectableTools()).thenReturn(List.of());
    when(d2.selectableTools()).thenReturn(List.of());

    when(d1.findTool("dup_same_id")).thenReturn(Optional.of(internal));
    when(d2.findTool("dup_same_id")).thenReturn(Optional.of(internal));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(d1, d2));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> composite.findTool("dup_same_id"));
    assertEquals("duplicate tool name across catalogs: dup_same_id", error.getMessage());
  }

  @Test
  void findToolDoesNotTreatOneSourceAsDuplicateWithItself() {
    // 意图：同一 delegate 同时声明 selectable 与 findTool 命中时只是一个来源，不得误判为跨源重复
    ToolContribution selectable = dummyContribution("single_source", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog delegate = mock(RuntimeToolCatalog.class);
    when(delegate.selectableTools()).thenReturn(List.of(selectable));
    when(delegate.findTool("single_source")).thenReturn(Optional.of(selectable));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(delegate));

    assertEquals(List.of(selectable), composite.selectableTools());
    assertEquals(Optional.of(selectable), composite.findTool("single_source"));
  }

  @Test
  void queriesDelegatesDynamicallyWithoutCaching() {
    // 意图：验证复合目录每次调用都现读 delegate，不进行任何缓存
    ToolContribution t1 = dummyContribution("name1", ToolVisibility.SELECTABLE);
    ToolContribution t2 = dummyContribution("name2", ToolVisibility.SELECTABLE);

    RuntimeToolCatalog delegate = mock(RuntimeToolCatalog.class);
    when(delegate.selectableTools()).thenReturn(List.of(t1)).thenReturn(List.of(t1, t2));

    CompositeRuntimeToolCatalog composite = new CompositeRuntimeToolCatalog(List.of(delegate));

    assertEquals(1, composite.selectableTools().size());
    assertEquals(2, composite.selectableTools().size());
  }

  private static ToolContribution dummyContribution(String toolName, ToolVisibility visibility) {
    Tool executableTool = mock(Tool.class);
    ToolDescriptor descriptor = descriptor(toolName);
    when(executableTool.descriptor()).thenReturn(descriptor);
    when(executableTool.requirements()).thenReturn(ToolRequirements.none());
    return new ToolContribution(
        new ContributionId(new ContributorId("test-contributor"), localName(toolName)),
        new AgentToolDefinition(descriptor, visibility),
        executableTool,
        ToolRequirements.none(),
        0);
  }

  /** 复制一个贡献，仅替换 scoped contribution identity，用于表达“同名但来源不同”。 */
  private static ToolContribution duplicateOf(ToolContribution origin, ContributionId id) {
    return new ToolContribution(
        id, origin.definition(), origin.tool(), origin.requirements(), origin.priority());
  }

  private static ToolDescriptor descriptor(String toolName) {
    return new ToolDescriptor(
        toolName,
        "description for " + toolName,
        "renderer",
        new InputSchema("{}", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static String localName(String toolName) {
    return toolName.replace('_', '-');
  }
}
