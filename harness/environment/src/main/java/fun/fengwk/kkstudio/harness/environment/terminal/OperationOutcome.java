package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 一次已准入操作经真实 Runtime 决议后的结果，或跨连接恢复时核对出的旧操作决议。
 *
 * <p>只有 {@link #WRITTEN} 表示操作确定已写入 PTY；{@link #NOT_WRITTEN} 表示在进入 native 之前已确定未执行； {@link
 * #OUTCOME_UNKNOWN} 表示操作可能已部分落地，必须冻结本 terminal writer 而不得授权或重放。
 */
public enum OperationOutcome {
  WRITTEN,
  NOT_WRITTEN,
  OUTCOME_UNKNOWN
}
