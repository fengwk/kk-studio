package fun.fengwk.kkstudio.harness.provider.anthropic;

/** Anthropic 思考协议的线缆模式。 */
public enum AnthropicThinkingMode {
  /**
   * 自适应思考模式（Claude 原生行为）。
   *
   * <p>编码 {@code thinking:{type:"adaptive"}} 与 {@code output_config:{effort:...}}。
   */
  ADAPTIVE,

  /**
   * 预算思考模式（MiniMax-M3 等兼容服务）。
   *
   * <p>编码 {@code thinking:{type:"enabled",budget_tokens:N}} 并省略 {@code output_config}。
   */
  BUDGET
}
