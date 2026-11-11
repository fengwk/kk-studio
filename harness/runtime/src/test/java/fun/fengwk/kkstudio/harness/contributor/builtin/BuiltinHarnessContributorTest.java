package fun.fengwk.kkstudio.harness.contributor.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.input.AskUserTool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.spi.ResourceReadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * BuiltinHarnessContributor 的全面目录冻结与完整能力清单测试。
 *
 * <p>验证 exact inventory (11 tools: 5 file + 4 environment + 1 internal task + 1 ask_user),
 * visibility/requirements/capability 映射, 稳定模型可见 name 和全局唯一性。
 */
class BuiltinHarnessContributorTest {

  @Test
  void contributorDescriptorMatchesSpecification() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(stubFileTools(), stubTool("task", ToolRequirements.none()));
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
        NullPointerException.class, () -> new BuiltinHarnessContributor(stubFileTools(), null));
  }

  @Test
  void constructorRejectsIncorrectOrSwappedTools() {
    // 文件工具顺序与名字必须与冻结的 5 项清单一致：把 read 换成别的名字即被拒绝
    List<Tool> wrongFileTools =
        List.of(
            stubTool("other", ToolRequirements.routed()),
            stubTool("write", ToolRequirements.routed()),
            stubTool("edit", ToolRequirements.routed()),
            stubTool("grep", ToolRequirements.routed()),
            stubTool("find", ToolRequirements.routed()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                wrongFileTools, stubTool("task", ToolRequirements.none())));

    // 文件工具数量不足同样被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                List.of(stubTool("read", ToolRequirements.routed())),
                stubTool("task", ToolRequirements.none())));

    // 传错 taskTool 名字
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuiltinHarnessContributor(
                stubFileTools(), stubTool("other", ToolRequirements.none())));

    // taskTool descriptor 为 null
    Tool nullDescTask = mock(Tool.class);
    when(nullDescTask.descriptor()).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuiltinHarnessContributor(stubFileTools(), nullDescTask));
  }

  @Test
  void catalogFreezesExactInventoryOf12ToolsAndAssociatedCapabilities() {
    List<Tool> fileTools = stubFileTools();
    Tool task = stubTool("task", ToolRequirements.none());
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(fileTools, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    // Descriptors
    assertEquals(1, catalog.descriptors().size());
    assertEquals(contributor.descriptor(), catalog.descriptors().get(0));
    assertEquals(new ContributorId("builtin"), catalog.descriptors().get(0).id());

    // Tools inventory: exactly 11 tools（含 1 个人工输入工具）
    List<ToolContribution> tools = catalog.tools();
    assertEquals(11, tools.size(), "exact total 11 tools expected");

    // Selectable tools: 1 read + 8 environment + 1 ask_user = 10 tools (task is INTERNAL)
    List<ToolContribution> selectables = catalog.selectableTools();
    assertEquals(10, selectables.size(), "exact 10 selectable tools expected");

    // 5 个逐调用路由的文件工具（SELECTABLE, localName=name, ROUTED）
    assertFileTool(
        catalog,
        "read",
        EnvironmentCapabilityIds.FS_READ,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
    assertFileTool(
        catalog,
        "write",
        EnvironmentCapabilityIds.FS_WRITE,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofMinutes(1));
    assertFileTool(
        catalog,
        "edit",
        EnvironmentCapabilityIds.FS_EDIT,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(1));
    assertFileTool(
        catalog,
        "grep",
        EnvironmentCapabilityIds.FS_GREP,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(15));
    assertFileTool(
        catalog,
        "find",
        EnvironmentCapabilityIds.FS_FIND,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(15));

    // 4 个只能在环境执行的工具（SELECTABLE, REQUIRED, EnvironmentCapabilityTool 实例）
    assertEnvironmentTool(
        catalog,
        "bash",
        "environment.bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMinutes(5));
    assertEnvironmentTool(
        catalog,
        "lsp_goto_definition",
        "environment.lsp-goto-definition",
        EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(3));
    assertEnvironmentTool(
        catalog,
        "lsp_workspace_symbols",
        "environment.lsp-workspace-symbols",
        EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(3));
    assertEnvironmentTool(
        catalog,
        "lsp_java_decompile",
        "environment.lsp-java-decompile",
        EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(3));

    // 1 个 Internal 工具 (INTERNAL visibility, NONE requirements)
    assertInternalTool(catalog, "task", "runtime.task", ToolRequirements.none());

    // 人工输入工具：Runtime 的 provenance 判据是 (contributor=builtin, name=ask_user)，因此身份必须在目录里冻结。
    assertAskUserTool(catalog);

    // Ensure all model-visible tool names and ContributionIds are unique
    Set<String> names = new HashSet<>();
    Set<ContributionId> contributionIds = new HashSet<>();
    for (ToolContribution toolContribution : tools) {
      assertTrue(names.add(toolContribution.definition().descriptor().name()));
      assertTrue(contributionIds.add(toolContribution.id()));
    }
  }

  /**
   * 验证内置 contributor 的模型工具面恰好是 5 个文件工具加 4 个环境专用工具，不泄漏任何 MCP 身份。
   *
   * <p>文件工具按每次调用的路径在 Platform VFS 与 BoundEnvironment 之间路由。
   */
  @Test
  void builtinToolsAreExactlyTheFiveFileToolsPlusFourHostCapabilities() {
    assertTrue(
        EnvironmentCapabilityCatalog.descriptors().stream()
            .noneMatch(descriptor -> descriptor.id().value().contains("mcp")));

    List<Tool> fileTools = stubFileTools();
    Tool task = stubTool("task", ToolRequirements.none());
    BuiltinHarnessContributor contributor = new BuiltinHarnessContributor(fileTools, task);

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
            EnvironmentCapabilityIds.PROCESS_EXEC,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);

    List<EnvironmentCapabilityTool> envTools =
        catalog.tools().stream()
            .map(ToolContribution::tool)
            .filter(EnvironmentCapabilityTool.class::isInstance)
            .map(EnvironmentCapabilityTool.class::cast)
            .toList();

    assertEquals(4, envTools.size(), "Environment capability backed tools count must be exactly 4");

    Set<EnvironmentCapabilityId> registeredCapabilityIds =
        envTools.stream().map(tool -> tool.capability().id()).collect(Collectors.toSet());

    assertEquals(expectedHostCapabilityIds, registeredCapabilityIds);

    // 2. 文件工具的模型可见 name 集合恰好是五个文件工具
    Set<String> expectedFileToolNames = Set.of("read", "write", "edit", "grep", "find");
    Set<String> registeredFileToolNames =
        catalog.tools().stream()
            .filter(t -> t.tool() instanceof FileTool)
            .map(t -> t.definition().descriptor().name())
            .collect(Collectors.toSet());

    assertEquals(expectedFileToolNames, registeredFileToolNames);

    // 3. 两类工具合起来是 9 个带宿主能力语义的工具（5 + 4）
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
                t -> t.tool() instanceof EnvironmentCapabilityTool || t.tool() instanceof FileTool)
            .map(t -> t.definition().descriptor().name())
            .collect(Collectors.toSet());

    assertEquals(expectedToolNames, registeredToolNames);
  }

  private static void assertFileTool(
      HarnessCatalog catalog,
      String toolName,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect,
      Duration defaultTimeout) {
    ToolContribution tool = catalog.findTool(toolName).orElseThrow();
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), toolName), tool.id());
    assertEquals(ToolRequirements.routed(), tool.requirements());
    assertEquals(EnvironmentSupport.ROUTED, tool.requirements().environmentSupport());

    assertInstanceOf(FileTool.class, tool.tool());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals(toolName, descriptor.name());
    assertEquals(toolName, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());

    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    assertEquals(capability.inputSchema(), descriptor.inputSchema());
    assertEquals(defaultTimeout, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());
    assertFalse(descriptor.description().contains("workdir"));
    if ("read".equals(toolName)) {
      // read 的模型提示词必须声明两种 vfs: 只读形态，避免模型猜测不存在的路径。
      assertTrue(descriptor.description().contains("resource://files/<uuid>"));
      assertTrue(descriptor.description().contains("vfs:/skills/<package>/<skill>/<relativePath>"));
    }

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
    if (capability.inputSchema().required().contains("workdir")) {
      // 模型提示词必须与 schema 的必填绝对 workdir 契约一致，避免生成必然被服务端拒绝的相对或缺省目录调用。
      assertTrue(descriptor.description().contains("Every call must include `workdir`"));
      assertTrue(descriptor.description().contains("expanded absolute directory"));
      assertFalse(descriptor.description().contains("workdir` defaults"));
    } else {
      assertFalse(
          descriptor.description().contains("workdir"),
          toolName + " prompt must not contain workdir");
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

  /** 人工输入工具：稳定身份 + NONE requirements + READ_ONLY 且没有执行 deadline（真正的等待由 Runtime 冻结）。 */
  private static void assertAskUserTool(HarnessCatalog catalog) {
    ToolContribution tool = catalog.findTool("ask_user").orElseThrow();
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertEquals(new ContributionId(new ContributorId("builtin"), "ask-user"), tool.id());
    assertEquals(ToolRequirements.none(), tool.requirements());
    assertInstanceOf(AskUserTool.class, tool.tool());

    ToolDescriptor descriptor = tool.definition().descriptor();
    assertEquals("ask_user", descriptor.name());
    assertEquals("ask_user", descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());

    assertEquals(tool, catalog.findTool(tool.id()).orElseThrow());
  }

  /**
   * bash 提示词必须描述宿主配置的 shell（{@code bash -lc <command>}），不得残留未渲染的模板占位符。
   *
   * <p>提示词中的占位符必须完全渲染，不得以字面量进入模型指令。
   */
  @Test
  void bashPromptDescribesConfiguredShellWithoutTemplatePlaceholders() {
    HarnessCatalog catalog =
        HarnessCatalog.from(
            List.of(
                new BuiltinHarnessContributor(
                    stubFileTools(), stubTool("task", ToolRequirements.none()))));

    String description =
        catalog.findTool("bash").orElseThrow().definition().descriptor().description();

    assertFalse(description.contains("${"), "环境工具提示词不得残留占位符：" + description);
    assertTrue(description.contains("bash -lc <command>"), description);
    assertTrue(description.contains("configured shell"), description);
  }

  /** 五个真实 FileTool：名字、顺序与 capability 绑定必须与 Contributor 冻结的清单一致。 */
  private static List<Tool> stubFileTools() {
    ResourceReadGateway vfsGateway = mock(ResourceReadGateway.class);
    return List.of(
        new FileTool(
            "read",
            FileTool.Operation.READ,
            "read.md",
            EnvironmentCapabilityIds.FS_READ,
            ToolSideEffect.READ_ONLY,
            vfsGateway),
        new FileTool(
            "write",
            FileTool.Operation.WRITE,
            "write.md",
            EnvironmentCapabilityIds.FS_WRITE,
            ToolSideEffect.IDEMPOTENT,
            vfsGateway),
        new FileTool(
            "edit",
            FileTool.Operation.EDIT,
            "edit.md",
            EnvironmentCapabilityIds.FS_EDIT,
            ToolSideEffect.NON_IDEMPOTENT,
            vfsGateway),
        new FileTool(
            "grep",
            FileTool.Operation.GREP,
            "grep.md",
            EnvironmentCapabilityIds.FS_GREP,
            ToolSideEffect.READ_ONLY,
            vfsGateway),
        new FileTool(
            "find",
            FileTool.Operation.FIND,
            "find.md",
            EnvironmentCapabilityIds.FS_FIND,
            ToolSideEffect.READ_ONLY,
            vfsGateway));
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
      public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
        return new ToolOutcome(new ToolResult(request.call().id(), List.of(), false, "{}"));
      }
    };
  }
}
