package fun.fengwk.kkstudio.plugin.goal;

import fun.fengwk.kkstudio.harness.plugin.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.PluginId;
import fun.fengwk.kkstudio.harness.plugin.PluginRegistrar;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;

/** 内置 Goal 插件定义。 */
public final class GoalPlugin implements HarnessPlugin {

  public static final PluginId ID = new PluginId("goal");
  public static final String STATE_TYPE = "state";

  private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(ID, "Goal", "2");

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(PluginRegistrar registrar) {
    registrar.registerCustomEntryType("state-type", STATE_TYPE);
    registrar.registerTool("create", new CreateGoalTool(), ToolVisibility.SELECTABLE);
    registrar.registerTool("get", new GetGoalTool(), ToolVisibility.SELECTABLE);
    registrar.registerTool("update", new UpdateGoalTool(), ToolVisibility.SELECTABLE);
    registrar.registerContextProjector("context", new GoalContextProjector());
  }
}
