package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 错误发生时的执行确定性分类。
 *
 * <p>{@link #NOT_EXECUTED} 表示可以确定相关操作从未执行；{@link #OUTCOME_UNKNOWN} 表示操作可能已部分落地、无法判定。控制器据此决定是安全重试还是
 * 冻结并重建 writer。
 */
public enum ErrorDisposition {
  NOT_EXECUTED,
  OUTCOME_UNKNOWN
}
