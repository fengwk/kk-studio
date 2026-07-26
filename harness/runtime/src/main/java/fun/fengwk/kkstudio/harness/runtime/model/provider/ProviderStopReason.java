package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** Provider 返回的规范化结束原因。 */
public enum ProviderStopReason {
  COMPLETED,
  TOOL_CALLS,
  LENGTH,
  CONTENT_FILTER,
  OTHER,
  CANCELLED
}
