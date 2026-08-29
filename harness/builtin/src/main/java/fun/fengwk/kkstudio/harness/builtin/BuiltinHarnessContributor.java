package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentPrompts;
import fun.fengwk.kkstudio.harness.builtin.goal.CreateGoalTool;
import fun.fengwk.kkstudio.harness.builtin.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.builtin.goal.GoalContextProjector;
import fun.fengwk.kkstudio.harness.builtin.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;

import java.util.Objects;
import java.util.Set;

/**
 * 第一方内置功能包 Contributor。
 *
 * <p>注册 12 个模型可见 Environment capability 工具、{@code load_skill} 与 {@code task} 内部 HOST 工具、 Goal
 * 工具、{@code goal.state} 自定义 Entry 类型与上下文投影器。
 */
public final class BuiltinHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("builtin");
  public static final String NAME = "Built-in";
  public static final String VERSION = "1";
  public static final String GOAL_STATE_TYPE = "goal.state";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final Tool loadSkillTool;
  private final Tool taskTool;

  public BuiltinHarnessContributor(Tool loadSkillTool, Tool taskTool) {
    this.loadSkillTool = Objects.requireNonNull(loadSkillTool, "loadSkillTool");
    this.taskTool = Objects.requireNonNull(taskTool, "taskTool");
    ToolDescriptor loadSkillDescriptor = this.loadSkillTool.descriptor();
    if (loadSkillDescriptor == null || !"load_skill".equals(loadSkillDescriptor.name())) {
      throw new IllegalArgumentException(
          "loadSkillTool descriptor name must be \"load_skill\", got "
              + (loadSkillDescriptor == null ? "null" : "\"" + loadSkillDescriptor.name() + "\""));
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

    // 12 Environment capability tools
    registerEnvironment(
        registrar,
        "environment.read",
        BuiltinToolIds.READ,
        "read",
        EnvironmentCapabilityIds.FS_READ,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.write",
        BuiltinToolIds.WRITE,
        "write",
        EnvironmentCapabilityIds.FS_WRITE,
        ToolSideEffect.IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.edit",
        BuiltinToolIds.EDIT,
        "edit",
        EnvironmentCapabilityIds.FS_APPLY_EDIT,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.apply-patch",
        BuiltinToolIds.APPLY_PATCH,
        "apply_patch",
        EnvironmentCapabilityIds.FS_APPLY_PATCH,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.bash",
        BuiltinToolIds.BASH,
        "bash",
        EnvironmentCapabilityIds.PROCESS_EXEC,
        ToolSideEffect.NON_IDEMPOTENT);
    registerEnvironment(
        registrar,
        "environment.grep",
        BuiltinToolIds.GREP,
        "grep",
        EnvironmentCapabilityIds.FS_SEARCH,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.find",
        BuiltinToolIds.FIND,
        "find",
        EnvironmentCapabilityIds.FS_FIND,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.lsp-goto-definition",
        BuiltinToolIds.LSP_GOTO_DEFINITION,
        "lsp_goto_definition",
        EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.lsp-workspace-symbols",
        BuiltinToolIds.LSP_WORKSPACE_SYMBOLS,
        "lsp_workspace_symbols",
        EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.lsp-java-decompile",
        BuiltinToolIds.LSP_JAVA_DECOMPILE,
        "lsp_java_decompile",
        EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.mcp-list-tools",
        BuiltinToolIds.MCP_LIST_TOOLS,
        "mcp_list_tools",
        EnvironmentCapabilityIds.MCP_LIST,
        ToolSideEffect.READ_ONLY);
    registerEnvironment(
        registrar,
        "environment.mcp-call-tool",
        BuiltinToolIds.MCP_CALL_TOOL,
        "mcp_call_tool",
        EnvironmentCapabilityIds.MCP_CALL,
        ToolSideEffect.NON_IDEMPOTENT);

    // Host tools
    registrar.registerTool(
        "runtime.load-skill", BuiltinToolIds.LOAD_SKILL, loadSkillTool, ToolVisibility.INTERNAL, 0);
    registrar.registerTool(
        "runtime.task", BuiltinToolIds.TASK, taskTool, ToolVisibility.INTERNAL, 0);

    // Goal tools & custom entry
    registrar.registerCustomEntryType("goal.state-type", GOAL_STATE_TYPE, 0);
    registrar.registerTool(
        "goal.create",
        BuiltinToolIds.GOAL_CREATE,
        new CreateGoalTool(),
        ToolVisibility.SELECTABLE,
        0);
    registrar.registerTool(
        "goal.get", BuiltinToolIds.GOAL_GET, new GetGoalTool(), ToolVisibility.SELECTABLE, 0);
    registrar.registerTool(
        "goal.update",
        BuiltinToolIds.GOAL_UPDATE,
        new UpdateGoalTool(),
        ToolVisibility.SELECTABLE,
        0);
    registrar.registerContextProjector("goal.context", new GoalContextProjector(), 0);
  }

  private static void registerEnvironment(
      HarnessRegistrar registrar,
      String localName,
      AgentToolId agentToolId,
      String toolName,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect) {
    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    ToolDescriptor descriptor =
        new ToolDescriptor(
            toolName,
            "1",
            EnvironmentPrompts.load(toolName + ".md"),
            toolName,
            capability.inputSchema(),
            sideEffect,
            capability.timeout());
    Tool tool = new EnvironmentCapabilityTool(descriptor, capability);
    registrar.registerTool(localName, agentToolId, tool, ToolVisibility.SELECTABLE, 0);
  }
}
