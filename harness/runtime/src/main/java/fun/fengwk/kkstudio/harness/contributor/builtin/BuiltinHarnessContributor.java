package fun.fengwk.kkstudio.harness.contributor.builtin;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentPrompts;
import fun.fengwk.kkstudio.harness.contributor.builtin.input.AskUserTool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 第一方内置功能包 Contributor。
 *
 * <p>集中注册 11 个内置工具（5 个逐调用路由的文件工具、{@code bash} 与 3 个 LSP 宿主工具、{@code task} internal 工具、 {@code
 * ask_user} 人工输入工具）。文件工具声明 {@link EnvironmentSupport#ROUTED}，{@code bash} 与 LSP 工具声明 {@link
 * EnvironmentSupport#REQUIRED}，{@code task} 与 {@code ask_user} 声明 {@link EnvironmentSupport#NONE}。
 * {@code ask_user} 的等待由 Runtime 在 dispatch 前冻结，本 Contributor 只提供它的模型可见契约。
 */
public final class BuiltinHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("builtin");
  public static final String NAME = "Built-in";
  public static final String VERSION = "1";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private static final List<String> FILE_TOOL_NAMES =
      List.of("read", "write", "edit", "grep", "find");

  private final List<Tool> fileTools;
  private final Tool taskTool;
  private final Tool skillLoadTool;

  public BuiltinHarnessContributor(List<Tool> fileTools, Tool taskTool) {
    this(fileTools, taskTool, null);
  }

  public BuiltinHarnessContributor(List<Tool> fileTools, Tool taskTool, Tool skillLoadTool) {
    this.fileTools = List.copyOf(Objects.requireNonNull(fileTools, "fileTools"));
    this.taskTool = Objects.requireNonNull(taskTool, "taskTool");
    this.skillLoadTool = skillLoadTool;
    if (this.fileTools.size() != FILE_TOOL_NAMES.size()) {
      throw new IllegalArgumentException("fileTools must contain exactly " + FILE_TOOL_NAMES);
    }
    for (int index = 0; index < FILE_TOOL_NAMES.size(); index++) {
      ToolDescriptor descriptor = this.fileTools.get(index).descriptor();
      String expected = FILE_TOOL_NAMES.get(index);
      if (descriptor == null || !expected.equals(descriptor.name())) {
        throw new IllegalArgumentException(
            "fileTools["
                + index
                + "] descriptor name must be \""
                + expected
                + "\", got "
                + (descriptor == null ? "null" : "\"" + descriptor.name() + "\""));
      }
    }
    ToolDescriptor taskDescriptor = this.taskTool.descriptor();
    if (taskDescriptor == null || !"task".equals(taskDescriptor.name())) {
      throw new IllegalArgumentException(
          "taskTool descriptor name must be \"task\", got "
              + (taskDescriptor == null ? "null" : "\"" + taskDescriptor.name() + "\""));
    }
    if (skillLoadTool != null
        && (skillLoadTool.descriptor() == null
            || !"load_skill".equals(skillLoadTool.descriptor().name()))) {
      throw new IllegalArgumentException("skillLoadTool descriptor name must be \"load_skill\"");
    }
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");

    // 5 个逐调用路由的文件工具（SELECTABLE, priority 0, routed）
    for (int index = 0; index < FILE_TOOL_NAMES.size(); index++) {
      registrar.registerTool(
          FILE_TOOL_NAMES.get(index), fileTools.get(index), ToolVisibility.SELECTABLE, 0);
    }

    // 只能在环境执行的内置工具：bash 与 3 个 LSP
    registerEnvironment(
        registrar,
        "environment.bash",
        "bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.lsp-goto-definition",
        "lsp_goto_definition",
        EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.lsp-workspace-symbols",
        "lsp_workspace_symbols",
        EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.lsp-java-decompile",
        "lsp_java_decompile",
        EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
        ToolSideEffect.READ_ONLY);

    // Internal server-side tools
    registrar.registerTool("runtime.task", taskTool, ToolVisibility.INTERNAL, 0);
    if (skillLoadTool != null) {
      registrar.registerTool("runtime.load-skill", skillLoadTool, ToolVisibility.INTERNAL, 0);
    }

    // 人工输入工具：Runtime 在 dispatch 前按 provenance 把内置 ask_user 冻结为 WAITING_INPUT。
    registrar.registerTool("ask-user", new AskUserTool(), ToolVisibility.SELECTABLE, 0);
  }

  private static void registerEnvironment(
      HarnessRegistrar registrar,
      String localName,
      String toolName,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect) {
    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    ToolDescriptor descriptor =
        new ToolDescriptor(
            toolName,
            EnvironmentPrompts.load(toolName + ".md"),
            toolName,
            capability.inputSchema(),
            sideEffect,
            capability.defaultTimeout());
    Tool tool = new EnvironmentCapabilityTool(descriptor, capability);
    registrar.registerTool(localName, tool, ToolVisibility.SELECTABLE, 0);
  }
}
