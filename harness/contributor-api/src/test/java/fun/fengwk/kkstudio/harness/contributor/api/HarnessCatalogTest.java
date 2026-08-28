package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** {@link HarnessCatalog} 的完整构建、确定性拓扑排序、多后端工具注册与强隔离/校验测试。 */
class HarnessCatalogTest {

  private static final ToolParamsSchema SCHEMA =
      new ToolParamsSchema("Test schema", Map.of(), Set.of(), false);

  /** 空 contributor 列表产生完全合法的空 catalog。 */
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
    assertTrue(catalog.findTool(new AgentToolId("base.bash")).isEmpty());
    assertTrue(catalog.findTool(new ContributionId(new ContributorId("core"), "bash")).isEmpty());
  }

  /** 拓扑排序确定性：无依赖按 ContributorId 字典序；有依赖按 requires 偏序优先。 */
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

  /** 传递依赖闭包正确汇总。 */
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

  /** 检测 requires 图中的循环依赖。 */
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

  /** 检测缺失依赖项。 */
  @Test
  void rejectsMissingRequirement() {
    ContributorDescriptor aDesc =
        new ContributorDescriptor(
            new ContributorId("a"), "A", "1.0", Set.of(new ContributorId("missing")));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessCatalog.from(List.of(HarnessContributor.of(aDesc, r -> {}))));
  }

  /** 检测重复 contributor id。 */
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

  /** 注册 HOST、DECLARATIVE 和 ENVIRONMENT_CAPABILITY 工具，以及 Custom Entry 和 Projector。 */
  @Test
  void registersAndIndexesAllContributionTypes() {
    Tool hostTool = dummyHostTool("host_tool");
    DeclarativeTool declTool =
        dummyDeclarativeTool("decl_tool", List.of(new StateDeclaration("state", StateMode.READ)));
    EnvironmentCapabilityDescriptor envCap =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ofSeconds(10));
    ToolDescriptor envDescriptor = descriptor("env_tool", Duration.ofSeconds(10));
    ContextProjector projector = view -> List.of();

    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());

    HarnessContributor contributor =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerCustomEntryType("state-entry", "state", 10);
              reg.registerHostTool(
                  "host", new AgentToolId("base.host"), hostTool, ToolVisibility.SELECTABLE, 5);
              reg.registerDeclarativeTool(
                  "decl", new AgentToolId("base.decl"), declTool, ToolVisibility.INTERNAL, 3);
              reg.registerEnvironmentCapabilityTool(
                  "env",
                  new AgentToolId("base.env"),
                  envDescriptor,
                  envCap,
                  ToolVisibility.SELECTABLE,
                  1);
              reg.registerContextProjector("proj", projector, 2);
            });

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // 验证 descriptor 查询
    assertTrue(catalog.findDescriptor(new ContributorId("core")).isPresent());

    // 验证 tools 查询与列表
    assertEquals(3, catalog.tools().size());
    assertEquals(2, catalog.selectableTools().size());

    ContributionId hostId = new ContributionId(new ContributorId("core"), "host");
    ContributionId declId = new ContributionId(new ContributorId("core"), "decl");
    ContributionId envId = new ContributionId(new ContributorId("core"), "env");

    ToolContribution hostContrib = catalog.findTool(hostId).orElseThrow();
    assertTrue(hostContrib instanceof HostToolContribution);
    assertEquals(AgentToolBackend.HOST, hostContrib.definition().backend());
    assertEquals(hostTool, ((HostToolContribution) hostContrib).tool());

    ToolContribution declContrib = catalog.findTool(declId).orElseThrow();
    assertTrue(declContrib instanceof DeclarativeToolContribution);
    assertEquals(AgentToolBackend.DECLARATIVE, declContrib.definition().backend());
    assertEquals(declTool, ((DeclarativeToolContribution) declContrib).tool());
    assertEquals(1, ((DeclarativeToolContribution) declContrib).stateAccesses().size());

    ToolContribution envContrib = catalog.findTool(envId).orElseThrow();
    assertTrue(envContrib instanceof EnvironmentCapabilityToolContribution);
    assertEquals(AgentToolBackend.ENVIRONMENT_CAPABILITY, envContrib.definition().backend());
    assertEquals(envCap, ((EnvironmentCapabilityToolContribution) envContrib).capability());

    // 验证按 name 和 AgentToolId 查询
    assertSame(hostContrib, catalog.findTool("host_tool").orElseThrow());
    assertSame(hostContrib, catalog.findTool(new AgentToolId("base.host")).orElseThrow());
    assertSame(declContrib, catalog.findTool("decl_tool").orElseThrow());
    assertSame(declContrib, catalog.findTool(new AgentToolId("base.decl")).orElseThrow());
    assertSame(envContrib, catalog.findTool("env_tool").orElseThrow());
    assertSame(envContrib, catalog.findTool(new AgentToolId("base.env")).orElseThrow());

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
              reg.registerHostTool(
                  "t2",
                  new AgentToolId("a.t2"),
                  dummyHostTool("a_t2"),
                  ToolVisibility.SELECTABLE,
                  5);
              reg.registerHostTool(
                  "t1",
                  new AgentToolId("a.t1"),
                  dummyHostTool("a_t1"),
                  ToolVisibility.SELECTABLE,
                  10);
            });

    HarnessContributor cb =
        HarnessContributor.of(
            bDesc,
            reg -> {
              // priority 100 但属于 b（依赖 a），必须排在 a 的工具之后
              reg.registerHostTool(
                  "t1",
                  new AgentToolId("b.t1"),
                  dummyHostTool("b_t1"),
                  ToolVisibility.SELECTABLE,
                  100);
            });

    HarnessCatalog catalog = HarnessCatalog.from(List.of(cb, ca));
    List<String> toolNames =
        catalog.tools().stream().map(t -> t.definition().descriptor().name()).toList();

    assertEquals(List.of("a_t1", "a_t2", "b_t1"), toolNames);
  }

  /** 同一 contributor 内 localName 重复检测。 */
  @Test
  void rejectsDuplicateLocalNameInSameContributor() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerHostTool(
                  "same",
                  new AgentToolId("core.t1"),
                  dummyHostTool("tool1"),
                  ToolVisibility.SELECTABLE);
              reg.registerHostTool(
                  "same",
                  new AgentToolId("core.t2"),
                  dummyHostTool("tool2"),
                  ToolVisibility.SELECTABLE);
            });

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** 全局 model-visible Tool name 重复检测。 */
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
                reg.registerHostTool(
                    "t1",
                    new AgentToolId("c1.t1"),
                    dummyHostTool("same_name"),
                    ToolVisibility.SELECTABLE));

    HarnessContributor c2 =
        HarnessContributor.of(
            desc2,
            reg ->
                reg.registerHostTool(
                    "t2",
                    new AgentToolId("c2.t2"),
                    dummyHostTool("same_name"),
                    ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(c1, c2)));
  }

  /** 全局 AgentToolId 重复检测。 */
  @Test
  void rejectsDuplicateAgentToolIdAcrossContributors() {
    ContributorDescriptor desc1 =
        new ContributorDescriptor(new ContributorId("c1"), "C1", "1.0", Set.of());
    ContributorDescriptor desc2 =
        new ContributorDescriptor(new ContributorId("c2"), "C2", "1.0", Set.of());

    HarnessContributor c1 =
        HarnessContributor.of(
            desc1,
            reg ->
                reg.registerHostTool(
                    "t1",
                    new AgentToolId("shared.id"),
                    dummyHostTool("name1"),
                    ToolVisibility.SELECTABLE));

    HarnessContributor c2 =
        HarnessContributor.of(
            desc2,
            reg ->
                reg.registerHostTool(
                    "t2",
                    new AgentToolId("shared.id"),
                    dummyHostTool("name2"),
                    ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(c1, c2)));
  }

  /** DeclarativeTool 访问未注册的 customType 时拒绝。 */
  @Test
  void rejectsDeclarativeToolAccessingUnregisteredState() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    DeclarativeTool tool =
        dummyDeclarativeTool(
            "decl_tool", List.of(new StateDeclaration("unregistered-state", StateMode.READ)));

    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg ->
                reg.registerDeclarativeTool(
                    "decl", new AgentToolId("core.decl"), tool, ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** DeclarativeTool 声明重复 stateAccess 时拒绝。 */
  @Test
  void rejectsDuplicateStateAccessOnDeclarativeTool() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    DeclarativeTool tool =
        dummyDeclarativeTool(
            "decl_tool",
            List.of(
                new StateDeclaration("state", StateMode.READ),
                new StateDeclaration("state", StateMode.WRITE)));

    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg -> {
              reg.registerCustomEntryType("s", "state");
              reg.registerDeclarativeTool(
                  "decl", new AgentToolId("core.decl"), tool, ToolVisibility.SELECTABLE);
            });

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** EnvironmentCapabilityTool schema 或 timeout 与 capability 不一致时拒绝。 */
  @Test
  void rejectsEnvironmentToolMismatchedCapability() {
    ContributorDescriptor desc =
        new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of());
    EnvironmentCapabilityDescriptor capability =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ofSeconds(10));
    ToolDescriptor mismatchDesc = descriptor("env_tool", Duration.ofSeconds(5));

    HarnessContributor contrib =
        HarnessContributor.of(
            desc,
            reg ->
                reg.registerEnvironmentCapabilityTool(
                    "env",
                    new AgentToolId("core.env"),
                    mismatchDesc,
                    capability,
                    ToolVisibility.SELECTABLE));

    assertThrows(IllegalArgumentException.class, () -> HarnessCatalog.from(List.of(contrib)));
  }

  /** 在 contribute 范围外调用 registrar 抛出 IllegalStateException。 */
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
            registrar.registerHostTool(
                "late",
                new AgentToolId("core.late"),
                dummyHostTool("late"),
                ToolVisibility.SELECTABLE));
  }

  private static Tool dummyHostTool(String name) {
    ToolDescriptor descriptor = descriptor(name, Duration.ZERO);
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return null;
      }
    };
  }

  private static DeclarativeTool dummyDeclarativeTool(
      String name, List<StateDeclaration> stateAccesses) {
    ToolDescriptor descriptor = descriptor(name, Duration.ZERO);
    return new DeclarativeTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public List<StateDeclaration> stateAccesses() {
        return stateAccesses;
      }

      @Override
      public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
        return DeclarativeToolResult.withoutIntents(
            new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{}"));
      }
    };
  }

  private static ToolDescriptor descriptor(String name, Duration timeout) {
    return new ToolDescriptor(
        name, "1.0", "Description", name, SCHEMA, ToolSideEffect.READ_ONLY, timeout);
  }
}
