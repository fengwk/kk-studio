package fun.fengwk.kkstudio.harness.provider.anthropic;

/** Anthropic 思考协议的线缆模式。 */
public enum AnthropicThinkingMode {
  /**
   * 自适应思考模式（Claude 原生行为）。
   *
   * <p>编码 {@code thinking:{type:"adaptive",display:"summarized"}} 与 {@code
   * output_config:{effort:...}}，不发送 {@code anthropic-beta}。
   */
  ADAPTIVE,

  /**
   * 预算思考模式（MiniMax-M3 等兼容服务）。
   *
   * <p>编码 {@code thinking:{type:"enabled",budget_tokens:N,display:"summarized"}} 并省略 {@code
   * output_config}；实际启用时发送 {@code anthropic-beta: interleaved-thinking-2025-05-14}。
   */
  BUDGET
}
