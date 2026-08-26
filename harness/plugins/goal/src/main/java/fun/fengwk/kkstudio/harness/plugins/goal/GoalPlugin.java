package fun.fengwk.kkstudio.harness.plugins.goal;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.BaseToolIds;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Set;

/** 内置 Goal 插件定义。 */
public final class GoalPlugin implements HarnessPlugin {

  public static final PluginId ID = new PluginId("goal");
  public static final String STATE_TYPE = "state";
  public static final AgentToolId CREATE_TOOL_ID = BaseToolIds.GOAL_CREATE;
  public static final AgentToolId GET_TOOL_ID = BaseToolIds.GOAL_GET;
  public static final AgentToolId UPDATE_TOOL_ID = BaseToolIds.GOAL_UPDATE;

  private static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(ID, "Goal", "2", Set.of());

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(PluginRegistrar registrar) {
    registrar.registerCustomEntryType("state-type", STATE_TYPE, 0);
    registrar.registerTool(
        "create", CREATE_TOOL_ID, new CreateGoalTool(), ToolVisibility.SELECTABLE, 0);
    registrar.registerTool("get", GET_TOOL_ID, new GetGoalTool(), ToolVisibility.SELECTABLE, 0);
    registrar.registerTool(
        "update", UPDATE_TOOL_ID, new UpdateGoalTool(), ToolVisibility.SELECTABLE, 0);
    registrar.registerContextProjector("context", new GoalContextProjector(), 0);
  }
}
