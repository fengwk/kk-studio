package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/** 工具执行结果：模型可见终态，或由持久 task Join 驱动的挂起。 */
public record ToolOutcome(ToolResult result, boolean waitingForChild) {

  public ToolOutcome(ToolResult result) {
    this(Objects.requireNonNull(result, "result"), false);
  }

  public ToolOutcome {
    if (waitingForChild == (result != null)) {
      throw new IllegalArgumentException(
          "tool outcome must contain either a result or a child-wait signal");
    }
  }

  /** task Join 已接受，待 Join 匹配后由 TOOL Work 交付结果。 */
  public static ToolOutcome deferredForChild() {
    return new ToolOutcome(null, true);
  }
}
