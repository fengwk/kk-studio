package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 规范化生成结束原因；tool intent 由 {@link ProviderResponse#toolCalls()} 独立表达。 */
public enum GenerationStopReason {
  COMPLETE,
  LENGTH,
  FILTERED
}
