package fun.fengwk.kkstudio.harness.runtime.subagent;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/** task/subagent 的进程级并发与预算参数；观察完全由 internal Thread change source 驱动，无轮询间隔。 */
public record SubagentConfig(
    int maxDepth,
    int maxConcurrency,
    Integer maxTotalConcurrency,
    Duration idleTimeout,
    int maxTurns) {

  public SubagentConfig {
    if (maxDepth < 1 || maxConcurrency < 1 || maxTurns < 1) {
      throw new IllegalArgumentException(
          "subagent maxDepth, maxConcurrency and maxTurns must be positive");
    }
    if (maxTotalConcurrency != null && maxTotalConcurrency < 1) {
      throw new IllegalArgumentException("subagent maxTotalConcurrency must be positive");
    }
    idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
    if (idleTimeout.isNegative()) {
      throw new IllegalArgumentException("subagent idleTimeout must not be negative");
    }
    if (!idleTimeout.isZero()) {
      idleTimeout = HarnessStoreTime.requireWholeMillisecondDuration(idleTimeout, "idleTimeout");
    }
  }
}
