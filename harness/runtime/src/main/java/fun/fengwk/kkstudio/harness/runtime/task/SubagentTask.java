package fun.fengwk.kkstudio.harness.runtime.task;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable relation between exactly one parent task Invocation and one child Run attempt. The
 * relation, rather than Session.parentInvocationId, is the idempotency and recovery fact.
 */
public record SubagentTask(
    long parentInvocationId,
    long parentSessionId,
    long childSessionId,
    long childRunId,
    String targetAgent,
    WorkspacePolicy workspacePolicy,
    int maxTurns,
    Duration idleTimeout,
    TaskState state,
    String reportJson,
    Instant createdAt,
    Instant updatedAt) {
  public SubagentTask {
    if (parentInvocationId <= 0 || parentSessionId <= 0 || childSessionId <= 0 || childRunId <= 0) {
      throw new IllegalArgumentException("subagent task ids must be positive");
    }
    if (targetAgent == null || targetAgent.isBlank()) {
      throw new IllegalArgumentException("targetAgent must not be blank");
    }
    workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    if (idleTimeout != null && (idleTimeout.isNegative() || idleTimeout.isZero())) {
      throw new IllegalArgumentException("idleTimeout must be positive when present");
    }
    state = Objects.requireNonNull(state, "state");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
