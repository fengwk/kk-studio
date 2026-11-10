package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/** 工具执行完成时的终态结果：模型可见的 {@link ToolResult}。 */
public record ToolOutcome(ToolResult result) {

  public ToolOutcome {
    result = Objects.requireNonNull(result, "result");
  }
}
