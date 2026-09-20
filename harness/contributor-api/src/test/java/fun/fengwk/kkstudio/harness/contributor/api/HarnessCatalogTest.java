package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** {@link HarnessCatalog} 的完整构建、确定性拓扑排序、统一工具注册与强隔离/校验测试。 */
class HarnessCatalogTest {

  private static final InputSchema SCHEMA =
      new InputSchema("Test schema", Map.of(), Set.of(), false);

  /** 验证空 contributor 列表产生完全合法的空 catalog。 */
  @Test
  void handlesEmptyContributorList() {
    HarnessCatalog catalog = HarnessCatalog.from(List.of());
    assertTrue(catalog.descriptors().isEmpty());
    assertTrue(catalog.tools().isEmpty());
    assertTrue(catalog.selectableTools().isEmpty());
    assertTrue(catalog.customEntryTypes().isEmpty());
    assertTrue(catalog.contextProjectors().isEmpty());
    assertTrue(catalog.findDescriptor(new ContributorId("core")).isEmpty());
    assertTrue(catalog.transitiveRequires(new ContributorId("core")).isEmpty());
    assertTrue(catalog.findTool("bash").isEmpty());
    assertTrue(catalog.findTool(new ContributionId(new ContributorId("core"), "bash")).isEmpty());
  }

  /** 验证拓扑排序确定性：无依赖按 ContributorId 字典序；有依赖按 requires 偏序优先。 */
  @Test
  void ordersContributorsByRequiresDagThenLexicographical() {
    ContributorDescriptor cDesc =
        new ContributorDescriptor(
            new ContributorId("c"), "C", "1.0", Set.of(new ContributorId("a")));
    ContributorDescriptor bDesc =
        new ContributorDescriptor(new ContributorId("b"), "B", "1.0", Set.of());
    ContributorDescriptor aDesc =
        new ContributorDescriptor(new ContributorId("a"), "A", "1.0", Set.of());

    HarnessContributor contribC = HarnessContributor.of(cDesc, reg -> {});
    HarnessContributor contribB = HarnessContributor.of(bDesc, reg -> {});
    HarnessContributor contribA = HarnessContributor.of(aDesc, reg -> {});

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contribC, contribB, contribA));

    // a 和 b 无依赖，字典序 a < b；c 依赖 a，因此排在 a 之后。
    assertEquals(
        List.of(new ContributorId("a"), new ContributorId("b"), new ContributorId("c")),
        catalog.descriptors().stream().map(ContributorDescriptor::id).toList());

    assertEquals(
        Set.of(new ContributorId("a")), catalog.transitiveRequires(new ContributorId("c")));
    assertEquals(Set.of(), catalog.transitiveRequires(new ContributorId("a")));
    assertEquals(Set.of(), catalog.transitiveRequires(new ContributorId("b")));
  }

  /** 验证传递依赖闭包正确汇总。 */
  @Test
  void computesTransitiveRequiresClosure() {
    ContributorDescriptor aDesc =
        new ContributorDescriptor(new ContributorId("a"), "A", "1.0", Set.of());
    ContributorDescriptor bDesc =
        new ContributorDescriptor(
            new ContributorId("b"), "B", "1.0", Set.of(new ContributorId("a")));
    ContributorDescriptor cDesc =
        new ContributorDescriptor(
            new ContributorId("c"), "C", "1.0", Set.of(new ContributorId("b")));

    HarnessCatalog catalog =
        HarnessCatalog.from(
            List.of(
                HarnessContributor.of(aDesc, r -> {}),
                HarnessContributor.of(bDesc, r -> {}),
                HarnessContributor.of(cDesc, r -> {})));

    assertEquals(
        Set.of(new ContributorId("a"), new ContributorId("b")),
        catalog.transitiveRequires(new ContributorId("c")));
  }

  /** 验证检测 requires 图中的循环依赖。 */
  @Test
  void rejectsRequiresCycle() {
    ContributorDescriptor aDesc =
        new ContributorDescriptor(
            new ContributorId("a"), "A", "1.0", Set.of(new ContributorId("b")));
    ContributorDescriptor bDesc =
        new ContributorDescriptor(
            new ContributorId("b"), "B", "1.0", Set.of(new ContributorId("a")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCatalog.from(
                List.of(
                    HarnessContributor.of(aDesc, r -> {}), HarnessContributor.of(bDesc, r -> {}))));
  }

  /** 验证检测缺失依赖项。 */
  @Test
  void rejectsMissingRequirement() {
    ContributorDescriptor aDesc =
        new ContributorDescriptor(
            new ContributorId("a"), "A", "1.0", Set.of(new ContributorId("missing")));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessCatalog.from(List.of(HarnessContributor.of(aDesc, r -> {}))));
  }

  /** 验证检测重复 contributor id。 */
  @Test
  void rejectsDuplicateContributorId() {
    ContributorDescriptor first =
        new ContributorDescriptor(new ContributorId("core"), "Core 1", "1.0", Set.of());
    ContributorDescriptor second =
        new ContributorDescriptor(new ContributorId("core"), "Core 2", "1.0", Set.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCatalog.from(
                List.of(
                    HarnessContributor.of(first, r -> {}),
                    HarnessContributor.of(second, r -> {}))));
  }

  /** 验证注册统一 Tool、Custom Entry 与 Projector，及其多维索引查询。 */
  @Test
  void registersAndIndexesAllContributionTypes() {
    Tool plainTool = dummyTool("plain_tool", ToolRequirements.none());
    Tool stateTool =
        dummyTool(
            "state_tool",
            new ToolRequirements(
                EnvironmentSupport.NONE, List.of(new StateDeclaration("state", StateMode.READ))));
    Tool envTool = dummyTool("env_tool", ToolRequirements.environment());
    ContextProjector projector = view -> List.of();

    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());

    HarnessContributor contributor =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerCustomEntryType("state-entry", "state", 10);
              reg.registerTool("plain", plainTool, ToolVisibility.SELECTABLE, 5);
              reg.registerTool("state", stateTool, ToolVisibility.INTERNAL, 3);
              reg.registerTool("env", envTool, ToolVisibility.SELECTABLE, 1);
              reg.registerContextProjector("proj", projector, 2);
            });

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // 验证 descriptor 查询
    assertTrue(catalog.findDescriptor(new ContributorId("core")).isPresent());

    // 验证 tools 查询与列表（两个 SELECTABLE，一个 INTERNAL）
    assertEquals(3, catalog.tools().size());
    assertEquals(2, catalog.selectableTools().size());

    ContributionId plainId = new ContributionId(new ContributorId("core"), "plain");
    ContributionId stateId = new ContributionId(new ContributorId("core"), "state");
    ContributionId envId = new ContributionId(new ContributorId("core"), "env");

    ToolContribution plainContrib = catalog.findTool(plainId).orElseThrow();
    assertEquals(plainTool, plainContrib.tool());
    assertEquals(ToolRequirements.none(), plainContrib.requirements());

    ToolContribution stateContrib = catalog.findTool(stateId).orElseThrow();
    assertEquals(stateTool, stateContrib.tool());
    assertEquals(1, stateContrib.requirements().stateAccesses().size());

    ToolContribution envContrib = catalog.findTool(envId).orElseThrow();
    assertEquals(envTool, envContrib.tool());
    assertEquals(EnvironmentSupport.REQUIRED, envContrib.requirements().environmentSupport());

    // 验证按模型可见 name 查询
    assertSame(plainContrib, catalog.findTool("plain_tool").orElseThrow());
    assertSame(stateContrib, catalog.findTool("state_tool").orElseThrow());
    assertSame(envContrib, catalog.findTool("env_tool").orElseThrow());

    // 验证 custom entry types
    assertEquals(1, catalog.customEntryTypes().size());
    assertTrue(catalog.findCustomEntryType(new ContributorId("core"), "state").isPresent());
    assertTrue(catalog.findCustomEntryType(new ContributorId("core"), "other").isEmpty());

    // 验证 context projectors
    assertEquals(1, catalog.contextProjectors().size());
    ContributionId projId = new ContributionId(new ContributorId("core"), "proj");
    assertTrue(catalog.findContextProjector(projId).isPresent());
  }

  /** 验证 tools 排序：requires 偏序优先，然后 priority 降序，然后 ContributionId 字典序。 */
  @Test
  void sortsContributionsByPriorityAndContributionId() {
    ContributorDescriptor aDesc =
        new ContributorDescriptor(new ContributorId("a"), "A", "1.0", Set.of());
    ContributorDescriptor bDesc =
        new ContributorDescriptor(
            new ContributorId("b"), "B", "1.0", Set.of(new ContributorId("a")));

    HarnessContributor ca =
        HarnessContributor.of(
            aDesc,
            reg -> {
              reg.registerTool(
                  "t2", dummyTool("a_t2", ToolRequirements.none()), ToolVisibility.SELECTABLE, 5);
              reg.registerTool(
                  "t1", dummyTool("a_t1", ToolRequirements.none()), ToolVisibility.SELECTABLE, 10);
            });

    HarnessContributor cb =
        HarnessContributor.of(
            bDesc,
            reg -> {
              // priority 100 但属于 b（依赖 a），必须排在 a 的工具之后
              reg.registerTool(
                  "t1", dummyTool("b_t1", ToolRequirements.none()), ToolVisibility.SELECTABLE, 100);
            });

    HarnessCatalog catalog = HarnessCatalog.from(List.of(cb, ca));
    List<String> toolNames =
        catalog.tools().stream().map(t -> t.definition().descriptor().name()).toList();

    assertEquals(List.of("a_t1", "a_t2", "b_t1"), toolNames);
  }

  /** 验证 priority 为 0 的 registerTool 重载方法行为。 */
  @Test
  void registersToolWithDefaultPriority() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    Tool tool = dummyTool("default_tool", ToolRequirements.none());
    HarnessContributor contrib =
        HarnessContributor.of(
            desc, reg -> reg.registerTool("def", tool, ToolVisibility.SELECTABLE));

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contrib));
    assertEquals(0, catalog.tools().get(0).priority());
  }

  /** 验证同一 contributor 内 localName 重复时抛出异常。 */
  @Test
  void rejectsDuplicateLocalNameInSameContributor() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerTool(
                  "same", dummyTool("tool1", ToolRequirements.none()), ToolVisibility.SELECTABLE);
              reg.registerTool(
                  "same", dummyTool("tool2", ToolRequirements.none()), ToolVisibility.SELECTABLE);
            });

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** 验证跨 contributor 注册同名 model-visible Tool 时抛出异常。 */
  @Test
  void rejectsDuplicateToolNameAcrossContributors() {
    ContributorDescriptor desc1 =
        new ContributorDescriptor(new ContributorId("c1"), "C1", "1.0", Set.of());
    ContributorDescriptor desc2 =
        new ContributorDescriptor(new ContributorId("c2"), "C2", "1.0", Set.of());

    HarnessContributor c1 =
        HarnessContributor.of(
            desc1,
            reg ->
                reg.registerTool(
                    "t1",
                    dummyTool("same_name", ToolRequirements.none()),
                    ToolVisibility.SELECTABLE));

    HarnessContributor c2 =
        HarnessContributor.of(
            desc2,
            reg ->
                reg.registerTool(
                    "t2",
                    dummyTool("same_name", ToolRequirements.none()),
                    ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(c1, c2)));
  }

  /** 验证同一 contributor 内重复注册 customType 抛出异常。 */
  @Test
  void rejectsDuplicateCustomEntryTypeInSameContributor() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerCustomEntryType("s1", "state");
              reg.registerCustomEntryType("s2", "state");
            });

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** 验证 Tool 声明访问本 contributor 未注册的 customType 时拒绝。 */
  @Test
  void rejectsToolAccessingUnregisteredState() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    Tool tool =
        dummyTool(
            "state_tool",
            new ToolRequirements(
                EnvironmentSupport.NONE,
                List.of(new StateDeclaration("unregistered-state", StateMode.READ))));

    HarnessContributor contrib =
        HarnessContributor.of(desc, reg -> reg.registerTool("t", tool, ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** 验证在 contribute 范围外调用 registrar 抛出 IllegalStateException。 */
  @Test
  void rejectsRegistrationOutsideContributeScope() {
    AtomicReference<HarnessRegistrar> captured = new AtomicReference<>();
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    HarnessContributor contrib = HarnessContributor.of(desc, captured::set);

    HarnessCatalog.from(List.of(contrib));

    HarnessRegistrar registrar = captured.get();
    assertThrows(
        IllegalStateException.class,
        () ->
            registrar.registerTool(
                "late", dummyTool("late", ToolRequirements.none()), ToolVisibility.SELECTABLE));
  }

  private static Tool dummyTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor = descriptor(name, Duration.ZERO);
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolRequirements requirements() {
        return requirements;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return null;
      }
    };
  }

  private static ToolDescriptor descriptor(String name, Duration timeout) {
    return new ToolDescriptor(name, "Description", name, SCHEMA, ToolSideEffect.READ_ONLY, timeout);
  }
}
