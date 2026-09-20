package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Optional;

/**
 * 成功 Provider 响应中有效 Tool 调用的可选历史 action 渲染端口：在 terminal ProviderResponse 持久化之前，为一次已归一化的 Tool
 * 调用解析并渲染简洁的自然语言 action。
 *
 * <p>实现方（Platform）负责按冻结 binding 解析当前 Tool 贡献并调用其可选 {@code ToolHistoryRenderer}；Runtime 只负责编排与容错。任何
 * “无渲染器 / 贡献已变化 / 渲染失败 / 参数不可归一化”都必须返回 {@link Optional#empty()}，使 Runtime 回退到通用自然语言描述—— 绝不因
 * 历史语义渲染而让模型请求失败。渲染结果必须确定性。
 */
public interface ToolHistoryActionResolver {

  /**
   * 渲染一次调用的历史 action。
   *
   * @param binding 该调用冻结的 Tool binding（携带贡献身份与冻结 Environment 名）
   * @param call 已按 binding schema 归一化的调用
   * @return 简洁 action 文本；不可解析时返回 empty
   */
  Optional<String> resolve(ToolBinding binding, ToolCall call);
}
