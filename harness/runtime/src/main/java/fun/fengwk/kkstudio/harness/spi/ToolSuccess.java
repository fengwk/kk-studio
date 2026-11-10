package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/** Gateway 已确认成功的一次 Tool terminal 事实：模型可见结果。 */
public record ToolSuccess(ToolResult result) {

  public ToolSuccess {
    result = Objects.requireNonNull(result, "result");
  }
}
