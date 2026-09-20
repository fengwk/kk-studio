package fun.fengwk.kkstudio.harness.builtin;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.ToolArguments;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;

import java.util.Optional;
import java.util.function.Function;

/**
 * 内建非环境工具的历史动作渲染器集合：{@code load_skill}、{@code task} 与 3 个 Goal 工具。
 *
 * <p>每个工具只暴露其语义所必需的最小事实：Skill 名、子代理类型（省略 maxTurns / session_id / 完整 prompt——委派产物已由结果表达）、 Goal
 * 目标文本或终态与原因。无法形成有意义动作时返回 empty，由 Runtime 中性回退（回退逐字保留全部 arguments）。
 */
public final class BuiltinHistoryRenderers {

  private BuiltinHistoryRenderers() {}

  /** {@code load_skill}：加载了哪个 skill 的完整正文。 */
  public static ToolHistoryRenderer loadSkill() {
    return field("name", name -> "load skill " + name);
  }

  /** {@code task}：委派给了哪个子代理；省略 maxTurns / session_id / 完整 prompt。 */
  public static ToolHistoryRenderer task() {
    return field("subagent_type", type -> "delegate to subagent " + type);
  }

  /** {@code create_goal}：设置的目标文本；tokenBudget 是执行预算，不属于语义动作。 */
  public static ToolHistoryRenderer createGoal() {
    return field("objective", objective -> "set goal: " + objective);
  }

  /** {@code get_goal}：无参数，动作恒定；具体目标由结果表达。 */
  public static ToolHistoryRenderer getGoal() {
    return request -> Optional.of("read the current goal");
  }

  /** {@code update_goal}：目标终态与原因。 */
  public static ToolHistoryRenderer updateGoal() {
    return request -> {
      JsonNode args = ToolArguments.parse(request.call().argumentsJson());
      String status = ToolArguments.text(args, "status");
      if (status == null) {
        return Optional.empty();
      }
      String reason = ToolArguments.text(args, "reason");
      String action = "mark the goal " + status;
      return Optional.of(reason == null ? action : action + ": " + reason);
    };
  }

  private static ToolHistoryRenderer field(String name, Function<String, String> builder) {
    return (ToolHistoryRenderRequest request) -> {
      String value = ToolArguments.text(ToolArguments.parse(request.call().argumentsJson()), name);
      return value == null ? Optional.empty() : Optional.of(builder.apply(value));
    };
  }
}
