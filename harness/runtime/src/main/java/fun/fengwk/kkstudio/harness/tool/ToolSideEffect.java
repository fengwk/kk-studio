package fun.fengwk.kkstudio.harness.tool;

/** 用于重试与未知结果处理的工具副作用级别。 */
public enum ToolSideEffect {
  READ_ONLY,
  IDEMPOTENT,
  NON_IDEMPOTENT
}
