package fun.fengwk.kkstudio.harness.tool.execution;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

/** Tool SPI 的流式结果回调。完成和失败互斥且至多发生一次。 */
public interface ToolExecutionListener {

  /** 接收非终止部分结果。 */
  void onPartial(ToolResult partial);

  /** 接收完整终止结果。 */
  void onComplete(ToolResult result);

  /** 接收执行失败。 */
  void onError(Throwable error);
}
