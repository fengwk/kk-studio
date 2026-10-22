package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Optional;

/**
 * Tool-owned 历史 action 渲染器：把一次已归一化的 Tool 调用渲染为一句简洁的自然语言动作。
 *
 * <p>当 Provider 无法再承载原生长 Tool 历史时，Runtime 用该动作替代被降级的调用，使模型仍能理解过去发生过什么。渲染结果因此必须：
 *
 * <ul>
 *   <li>确定性：同一输入恒返回同一文本；
 *   <li>只描述动作本身：结果由 ToolResult 单独表达，不臆测调用未携带的信息，省略超时、分页、版本游标等非语义执行控制参数；
 *   <li>不暴露 toolCallId，也不模拟 Tool 协议文本（不提 “tool call” / “tool result”）。
 * </ul>
 *
 * <p>Runtime 在成功 ProviderResponse 持久化之前调用本渲染器并冻结其返回值。返回 empty、抛出异常或返回 blank 一律按“未提供映射” 处理：Runtime
 * 回退到通用自然语言描述，绝不因此让模型请求失败。
 */
@FunctionalInterface
public interface ToolHistoryRenderer {

  /** 返回简洁动作描述；empty 表示本调用不提供语义动作，由 Runtime 回退。实现不得抛出异常。 */
  Optional<String> render(ToolHistoryRenderRequest request);
}
