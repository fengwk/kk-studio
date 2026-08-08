package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/** Gateway 已确认成功的一次 Tool terminal 事实：模型可见结果与待原子应用的 durable branch effects。 */
public record ToolSuccess(ToolResult result, ToolEffectBatch effects) {

  public ToolSuccess {
    result = Objects.requireNonNull(result, "result");
    effects = Objects.requireNonNull(effects, "effects");
  }

  public static ToolSuccess withoutEffects(ToolResult result) {
    return new ToolSuccess(result, ToolEffectBatch.EMPTY);
  }
}
