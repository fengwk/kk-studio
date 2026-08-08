package fun.fengwk.kkstudio.plugin.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.plugin.PluginStateDeclaration;
import fun.fengwk.kkstudio.harness.plugin.PluginStateMode;
import fun.fengwk.kkstudio.harness.plugin.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.PluginToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 创建或替换当前 branch Goal 全量快照。 */
public final class CreateGoalTool implements PluginTool {

  public static final String NAME = "create_goal";
  public static final String VERSION = "2";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          ToolType.PLATFORM,
          GoalPrompts.text("create-goal.md"),
          NAME,
          new ToolParamsSchema(
              "创建或替换当前 branch 的 durable goal。",
              Map.of(
                  "objective",
                  new ToolStringSchema("可由证据核验的目标，涵盖结果、验证面、约束、边界、迭代策略和阻塞停止条件。"),
                  "tokenBudget",
                  new ToolIntegerSchema("仅在用户明确要求时提供的可选正数 token 预算。")),
              Set.of("objective"),
              false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<PluginStateDeclaration> stateAccesses() {
    return List.of(new PluginStateDeclaration(GoalPlugin.STATE_TYPE, PluginStateMode.WRITE));
  }

  @Override
  public PluginToolResult execute(PluginToolContext context, ToolCall call) {
    try {
      ObjectNode arguments = GoalToolSupport.arguments(call, DESCRIPTOR);
      String objective = GoalToolSupport.requiredNonBlankText(arguments, "objective");
      Long tokenBudget = GoalToolSupport.optionalPositiveLong(arguments, "tokenBudget");
      GoalState current = GoalToolSupport.latest(context.branch()).orElse(null);
      Instant now = GoalToolSupport.timestamp(context.executedAt());
      GoalState state =
          new GoalState(
              objective,
              tokenBudget,
              GoalStatus.ACTIVE,
              null,
              current == null ? now : current.createdAt(),
              now);
      return GoalToolSupport.stateChange(call, state);
    } catch (RuntimeException error) {
      return GoalToolSupport.error(call, error);
    }
  }
}
