package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/** task/subagent 的进程级并发、预算与观察参数。 */
public record SubagentConfig(
    int maxDepth,
    int maxConcurrency,
    Integer maxTotalConcurrency,
    Duration idleTimeout,
    int maxTurns,
    Duration pollInterval) {

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
    pollInterval =
        HarnessStoreTime.requireWholeMillisecondDuration(
            Objects.requireNonNull(pollInterval, "pollInterval"), "pollInterval");
    if (pollInterval.isZero()) {
      throw new IllegalArgumentException("subagent pollInterval must be positive");
    }
  }
}
