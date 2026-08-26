package fun.fengwk.kkstudio.harness.tool.capability;

import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;

/**
 * Environment Capability 的流式结果回调。
 *
 * <p>事件序列必须为 {@code PARTIAL* -> exactly one terminal}：partial 可以有多个且必须保持发送顺序，终止事件只能是一次 {@link
 * #onComplete(EnvironmentCapabilityResult)} 或 {@link #onError(Throwable)}。终止事件之后的事件必须由实现 fence
 * 并视为无效。
 *
 * <p>partial 结果只允许包含 {@link TextToolContent} 或 {@link JsonToolContent}；{@link BinaryToolContent} 与
 * {@link ResourceToolContent} 只能出现在 terminal 结果中。该约束与现有 Daemon streaming contract 保持一致。
 *
 * <p>该接口只提供非阻塞回调，不提供 blocking、等待或结果拉取 API。
 */
public interface EnvironmentCapabilityExecutionListener {

  /** 接收非终止部分结果。 */
  void onPartial(EnvironmentCapabilityResult partial);

  /** 接收完整终止结果。 */
  void onComplete(EnvironmentCapabilityResult result);

  /** 接收执行失败。 */
  void onError(Throwable error);
}
