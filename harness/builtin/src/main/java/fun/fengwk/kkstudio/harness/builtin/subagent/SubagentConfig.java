package fun.fengwk.kkstudio.harness.builtin.subagent;

/**
 * task/subagent 的进程级并发与预算参数；观察完全由 internal Thread change source 驱动，无轮询间隔。
 *
 * <p>{@code maxTotalConcurrency} 为 0 表示不设树级上限；{@code maxTurns} 是未显式指定 {@code max_turns} 时的软预算默认值。
 */
public record SubagentConfig(
    int maxDepth, int maxConcurrency, int maxTotalConcurrency, int maxTurns) {

  public SubagentConfig {
    if (maxDepth < 1 || maxConcurrency < 1 || maxTurns < 1) {
      throw new IllegalArgumentException(
          "subagent maxDepth, maxConcurrency and maxTurns must be positive");
    }
    if (maxTotalConcurrency < 0) {
      throw new IllegalArgumentException("subagent maxTotalConcurrency must not be negative");
    }
  }
}
