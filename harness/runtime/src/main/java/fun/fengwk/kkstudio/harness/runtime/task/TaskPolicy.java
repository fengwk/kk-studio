package fun.fengwk.kkstudio.harness.runtime.task;

import java.time.Duration;

/** Frozen subagent limits resolved from the parent or target AgentSnapshot. */
public record TaskPolicy(
    int maxDepth,
    int maxDirect,
    Integer maxTotal,
    int maxTurns,
    Duration idleTimeout) {
  public static final int DEFAULT_MAX_DEPTH = 2;
  public static final int DEFAULT_MAX_DIRECT = 10;
  public static final int DEFAULT_MAX_TURNS = 50;

  public TaskPolicy {
    if (maxDepth <= 0 || maxDirect <= 0 || maxTurns <= 0) {
      throw new IllegalArgumentException("task limits must be positive");
    }
    if (maxTotal != null && maxTotal <= 0) {
      throw new IllegalArgumentException("maxTotal must be positive when present");
    }
    if (idleTimeout != null && (idleTimeout.isNegative() || idleTimeout.isZero())) {
      throw new IllegalArgumentException("idleTimeout must be positive when present");
    }
  }

  public static TaskPolicy defaults() {
    return new TaskPolicy(DEFAULT_MAX_DEPTH, DEFAULT_MAX_DIRECT, null, DEFAULT_MAX_TURNS, null);
  }
}
