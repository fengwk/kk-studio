package fun.fengwk.kkstudio.harness.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BuiltinHarnessContributor 的全面目录冻结与完整能力清单测试。
 *
 * <p>验证 exact inventory (12 tools: 1 read + 8 environment + 1 internal task + 2 goal),
 * visibility/requirements/capability 映射, 稳定模型可见 name, goal.progress ownership（无 Goal projector）,
 * 和全局唯一性。
 */
class BuiltinHarnessContributorTest {

  @Test
  void contributorDescriptorMatchesSpecification() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(stubReadTool(), stubTool("task", ToolRequirements.none()));
    ContributorDescriptor descriptor = contributor.descriptor();

    assertEquals(new ContributorId("builtin"), descriptor.id());
    assertEquals("Built-in", descriptor.name());
    assertEquals("1", descriptor.version());
    assertTrue(descriptor.requires().isEmpty());
  }

  @Test
  void nullConstructorArgumentsAreRejected() {
    assertThrows(
        NullPointerException.class,
        () -> new BuiltinHarnessContributor(null, stubTool("task", ToolRequirements.none())));
    assertThrows(
        NullPointerException.class, () -> new BuiltinHarnessContributor(stubReadTool(), null));
  }

  @Test
  void constructorRejectsIncorrectOrSwappedTools() {
    // 传错 readTool 名字
    ReadTool badReadTool = mock(ReadTool.class);
    ToolDescriptor badReadDesc =
        new ToolDescriptor(
            "other",
            "desc",
            "other",
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    when(badReadTool.descriptor()).thenReturn(badReadDesc);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(badReadTool, stubTool("task", ToolRequirements.none())));

    // readTool descriptor 为 null
    ReadTool nullDescReadTool = mock(ReadTool.class);
    when(nullDescReadTool.descriptor()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                nullDescReadTool, stubTool("task", ToolRequirements.none())));

    // 传错 taskTool 名字
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                stubReadTool(), stubTool("other", ToolRequirements.none())));

    // taskTool descriptor 为 null
    Tool nullDescTask = mock(Tool.class);
    when(nullDescTask.descriptor()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuiltinHarnessContributor(stubReadTool(), nullDescTask));
  }

  @Test
  void catalogFreezesExactInventoryOf12ToolsAndAssociatedCapabilities() {
    ReadTool readTool = stubReadTool();
    Tool task = stubTool("task", ToolRequirements.none());
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(readTool, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // Descriptors
    assertEquals(1, catalog.descriptors().size());
    assertEquals(contributor.descriptor(), catalog.descriptors().get(0));
    assertTrue(catalog.findDescriptor(new ContributorId("builtin")).isPresent());

    // Tools inventory: exactly 12 tools（Goal 正文由用户维护，没有 create_goal）
    List<ToolContribution> tools = catalog.tools();
    assertEquals(12, tools.size(), "exact total 12 tools expected");

    // Selectable tools: 1 read + 8 environment + 2 goal = 11 tools (task is INTERNAL)
    List<ToolContribution> selectables = catalog.selectableTools();
    assertEquals(11, selectables.size(), "exact 11 selectable tools expected");

    // 统一 read 工具（SELECTABLE, localName=read, name=read, OPTIONAL, ReadTool 实例）
    assertReadTool(catalog);

    // 8 个 Environment Capability 工具（SELECTABLE, REQUIRED, EnvironmentCapabilityTool 实例）
    assertEnvironmentTool(
        catalog,
        "write",
        "environment.write",
        EnvironmentCapabilityIds.FS_WRITE,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "edit",
        "environment.edit",
        EnvironmentCapabilityIds.FS_EDIT,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "bash",
        "environment.bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(5));
    assertEnvironmentTool(
        catalog,
        "grep",
        "environment.grep",
        EnvironmentCapabilityIds.FS_GREP,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "find",
        "environment.find",
        EnvironmentCapabilityIds.FS_FIND,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
    assertEnvironmentTool(
        catalog,
        "lsp_goto_definition",
        "environment.lsp-goto-definition",
        EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));
    assertEnvironmentTool(
        catalog,
        "lsp_workspace_symbols",
        "environment.lsp-workspace-symbols",
        EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));
    assertEnvironmentTool(
        catalog,
        "lsp_java_decompile",
        "environment.lsp-java-decompile",
        EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(2));

    // 1 个 Internal 工具 (INTERNAL visibility, NONE requirements)
    assertInternalTool(catalog, "task", "runtime.task", ToolRequirements.none());

    // 2 个 Goal 工具 (SELECTABLE visibility, NONE requirements + progress state access)
    assertTrue(catalog.findTool("create_goal").isEmpty(), "Goal 正文由用户维护，没有创建工具");
    assertGoalTool(catalog, "get_goal", "goal.get", ToolSideEffect.READ_ONLY, StateMode.READ);
    assertGoalTool(
        catalog, "update_goal", "goal.update", ToolSideEffect.IDEMPOTENT, StateMode.WRITE);

    // Custom entry types: exactly goal.progress ownership
    assertEquals(1, catalog.customEntryTypes().size());
    assertTrue(
        catalog.findCustomEntryType(new ContributorId("builtin"), "goal.progress").isPresent());
    assertEquals(
        "goal.progress-type",
        catalog
            .findCustomEntryType(new ContributorId("builtin"), "goal.progress")
            .orElseThrow()
            .id()
            .localName());

    // 没有任何 Goal context projector：Goal 绝不提升为 systemInstruction。
    assertTrue(catalog.contextProjectors().isEmpty());

    // Ensure all model-visible tool names and ContributionIds are unique
    Set<String> names = new HashSet<>();
    Set<ContributionId> contributionIds = new HashSet<>();
    for (ToolContribution toolContribution : tools) {
      assertTrue(names.add(toolContribution.definition().descriptor().name()));
      assertTrue(contributionIds.add(toolContribution.id()));
    }
  }

  /**
   * 验证 BuiltinHarnessContributor 只注册固定的环境相关能力工具，绝不泄漏任何 MCP 相关工具身份。
   *
   * <p>环境能力目录本身已不含 MCP：Platform 只通过 Streamable HTTP 在 Backend 内实现 MCP，因此内置 contributor
   * 的模型工具集合必须恰好等于工作目录与文件读取相关的 9 项能力（统一 read + 8 个宿主能力）。
   */
  @Test
  void builtinToolsAreExactlyReadPlusTheEightHostCapabilities() {
    assertTrue(
        EnvironmentCapabilityCatalog.descriptors().stream()
            .noneMatch(descriptor -> descriptor.id().value().contains("mcp")));

    ReadTool readTool = stubReadTool();
    Tool task = stubTool("task", ToolRequirements.none());
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(readTool, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // 1. 断言没有任何已注册工具名称或 rendererKey 泄漏 MCP 身份
    for (ToolContribution tool : catalog.tools()) {
      ToolDescriptor descriptor = tool.definition().descriptor();
      assertFalse(
          descriptor.name().toLowerCase().contains("mcp"),
          "Registered tool name must not contain mcp: " + descriptor.name());
      assertFalse(
          descriptor.rendererKey().toLowerCase().contains("mcp"),
          "Registered tool rendererKey must not contain mcp: " + descriptor.rendererKey());
    }

    Set<EnvironmentCapabilityId> expectedHostCapabilityIds =
        Set.of(
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_EDIT,
            EnvironmentCapabilityIds.PROCESS_EXEC,
            EnvironmentCapabilityIds.FS_GREP,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);

    List<EnvironmentCapabilityTool> envTools =
        catalog.tools().stream()
            .map(ToolContribution::tool)
            .filter(EnvironmentCapabilityTool.class::isInstance)
            .map(EnvironmentCapabilityTool.class::cast)
            .toList();

    assertEquals(8, envTools.size(), "Environment capability backed tools count must be exactly 8");

    Set<EnvironmentCapabilityId> registeredCapabilityIds =
        envTools.stream().map(tool -> tool.capability().id()).collect(Collectors.toSet());

    assertEquals(expectedHostCapabilityIds, registeredCapabilityIds);

    // 2. 断言已注册的环境工具模型可见 name 集合恰好等于固定的 9 个内置工具名（1 个 read + 8 个宿主能力）
    Set<String> expectedToolNames =
        Set.of(
            "read",
            "write",
            "edit",
            "bash",
            "grep",
            "find",
            "lsp_goto_definition",
            "lsp_workspace_symbols",
            "lsp_java_decompile");

    Set<String> registeredToolNames =
        catalog.tools().stream()
            .filter(
                t -> t.tool() instanceof EnvironmentCapabilityTool || t.tool() instanceof ReadTool)
            .map(t -> t.definition().descriptor().name())
            .collect(Collectors.toSet());

    assertEquals(expectedToolNames, registeredToolNames);
  }

  private static void assertReadTool(HarnessCatalog catalog) {
    ToolContribution tool = catalog.findTool("read").orElseThrow();
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), "read"), tool.id());
    assertEquals(ToolRequirements.optionalEnvironment(), tool.requirements());
    assertEquals(EnvironmentSupport.OPTIONAL, tool.requirements().environmentSupport());

    assertInstanceOf(ReadTool.class, tool.tool());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals("read", descriptor.name());
    assertEquals("read", descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());

    EnvironmentCapabilityDescriptor fsRead =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    assertEquals(fsRead.inputSchema(), descriptor.inputSchema());
    assertEquals(fsRead.defaultTimeout(), descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());

    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertEnvironmentTool(
      HarnessCatalog catalog,
      String toolName,
      String localName,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect,
      Duration defaultTimeout) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(ToolRequirements.environment(), tool.requirements());
    assertEquals(EnvironmentSupport.REQUIRED, tool.requirements().environmentSupport());

    assertInstanceOf(EnvironmentCapabilityTool.class, tool.tool());
    EnvironmentCapabilityTool envCapabilityTool = (EnvironmentCapabilityTool) tool.tool();

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(defaultTimeout, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());
    if (EnvironmentCapabilityCatalog.requiresWorkdir(capabilityId)) {
      // 模型提示词必须与 schema 的必填绝对 workdir 契约一致，避免生成必然被服务端拒绝的相对或缺省目录调用。
      assertTrue(descriptor.description().contains("Every call must include `workdir`"));
      assertTrue(descriptor.description().contains("expanded absolute directory"));
      assertFalse(descriptor.description().contains("workdir` defaults"));
    }

    assertEquals(capability.inputSchema(), descriptor.inputSchema());
    assertEquals(capability.defaultTimeout(), descriptor.defaultTimeout());
    assertEquals(capability, envCapabilityTool.capability());

    // Lookup by model-visible name and ContributionId
    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertInternalTool(
      HarnessCatalog catalog,
      String toolName,
      String localName,
      ToolRequirements expectedRequirements) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(ToolVisibility.INTERNAL, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(expectedRequirements, tool.requirements());

    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static void assertGoalTool(
      HarnessCatalog catalog,
      String toolName,
      String localName,
      ToolSideEffect sideEffect,
      StateMode mode) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), localName), tool.id());
    assertEquals(
        new ToolRequirements(
            EnvironmentSupport.NONE,
            List.of(new StateDeclaration(BuiltinHarnessContributor.GOAL_PROGRESS_TYPE, mode))),
        tool.requirements());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());

    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  private static ReadTool stubReadTool() {
    return new ReadTool((request, listener) -> null);
  }

  private static Tool stubTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            name + " description",
            name,
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
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
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
      }
    };
  }
}
