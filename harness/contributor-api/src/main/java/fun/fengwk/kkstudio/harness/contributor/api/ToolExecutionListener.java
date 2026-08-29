package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/** Tool SPI 的流式结果回调。完成和失败互斥且至多发生一次。 */
public interface ToolExecutionListener {

  /** 接收非终止部分结果。 */
  void onPartial(ToolResult partial);

  /** 接收完整终止结果（包含 effects）。 */
  void onComplete(ToolOutcome outcome);

  /** 便捷方法：接收不带 effects 的完整终止结果。 */
  default void onComplete(ToolResult result) {
    Objects.requireNonNull(result, "result");
    onComplete(ToolOutcome.withoutEffects(result));
  }

  /** 接收执行失败。 */
  void onError(Throwable error);
}
