package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentPrompts;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.builtin.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Objects;
import java.util.Set;

/**
 * 第一方内置功能包 Contributor。
 *
 * <p>集中注册 12 个内置工具（统一 {@code read}、8 个宿主 Environment capability 工具、{@code task} internal 工具、 2 个
 * Goal 工具）与 {@code goal.progress} 自定义 Entry 类型。其中 {@code read} 声明 {@link
 * EnvironmentSupport#OPTIONAL}，其余 8 个宿主工具声明 {@link EnvironmentSupport#REQUIRED}， {@code task} 与
 * Goal 工具声明 {@link EnvironmentSupport#NONE}。目标正文只由用户在 branch settings 中维护，因此没有创建工具、也没有把 Goal 提升为
 * systemInstruction 的 context projector。
 */
public final class BuiltinHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("builtin");
  public static final String NAME = "Built-in";
  public static final String VERSION = "1";
  public static final String GOAL_PROGRESS_TYPE = "goal.progress";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final ReadTool readTool;
  private final Tool taskTool;

  public BuiltinHarnessContributor(ReadTool readTool, Tool taskTool) {
    this.readTool = Objects.requireNonNull(readTool, "readTool");
    this.taskTool = Objects.requireNonNull(taskTool, "taskTool");
    ToolDescriptor readDescriptor = this.readTool.descriptor();
    if (readDescriptor == null || !"read".equals(readDescriptor.name())) {
      throw new IllegalArgumentException(
          "readTool descriptor name must be \"read\", got "
              + (readDescriptor == null ? "null" : "\"" + readDescriptor.name() + "\""));
    }
    ToolDescriptor taskDescriptor = this.taskTool.descriptor();
    if (taskDescriptor == null || !"task".equals(taskDescriptor.name())) {
      throw new IllegalArgumentException(
          "taskTool descriptor name must be \"task\", got "
              + (taskDescriptor == null ? "null" : "\"" + taskDescriptor.name() + "\""));
    }
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");

    // 统一 read 工具（SELECTABLE, priority 0, optionalEnvironment）
    registrar.registerTool("read", readTool, ToolVisibility.SELECTABLE, 0);

    // 8 个 Environment capability 工具（write/edit/bash/grep/find + 3 个 LSP）
    registerEnvironment(
        registrar,
        "environment.write",
        "write",
        EnvironmentCapabilityIds.FS_WRITE,
        ToolSideEffect.IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.edit",
        "edit",
        EnvironmentCapabilityIds.FS_EDIT,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.bash",
        "bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.grep",
        "grep",
        EnvironmentCapabilityIds.FS_GREP,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.find",
        "find",
        EnvironmentCapabilityIds.FS_FIND,
        ToolSideEffect.READ_ONLY);
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

    // Internal server-side tool
    registrar.registerTool("runtime.task", taskTool, ToolVisibility.INTERNAL, 0);

    // Goal tools & custom entry: 目标正文由用户维护，Agent 只能读取并报告进度。
    registrar.registerCustomEntryType("goal.progress-type", GOAL_PROGRESS_TYPE, 0);
    registrar.registerTool("goal.get", new GetGoalTool(), ToolVisibility.SELECTABLE, 0);
    registrar.registerTool("goal.update", new UpdateGoalTool(), ToolVisibility.SELECTABLE, 0);
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
