package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 规范化生成结束原因；tool intent 由 {@link ProviderResponse#toolCalls()} 独立表达。 */
public enum GenerationStopReason {
  /** Provider 正常结束本次生成，包括自然停止或工具调用结束。 */
  COMPLETE,

  /** 生成内容达到最大 token 上限导致截断。 */
  LENGTH,

  /** 生成因命中内容安全过滤策略而结束。 */
  FILTERED,

  /** Provider 协议要求立即续写本次生成（例如上游以协议终止态暂停）：本次响应不得携带任何工具意图。 */
  CONTINUE
}
