package fun.fengwk.kkstudio.harness.runtime.task;

import java.time.Instant;
import java.util.Objects;

/** 父子 Agent 任务关系；child 以 durable Thread 表达。 */
public record SubagentTask(
    long parentInvocationId,
    long parentSessionId,
    long parentThreadId,
    long childSessionId,
    long childThreadId,
    String targetAgent,
    WorkingCopyPolicy workingCopyPolicy,
    String workingCopyRevision,
    int maxTurns,
    TaskState state,
    String reportJson,
    Instant createdAt,
    Instant updatedAt) {

  public SubagentTask {
    if (parentInvocationId <= 0
        || parentSessionId <= 0
        || parentThreadId <= 0
        || childSessionId <= 0
        || childThreadId <= 0) {
      throw new IllegalArgumentException("task identity ids must be positive");
    }
    targetAgent = Objects.requireNonNull(targetAgent, "targetAgent");
    if (targetAgent.isBlank()) {
      throw new IllegalArgumentException("targetAgent must not be blank");
    }
    workingCopyPolicy = Objects.requireNonNull(workingCopyPolicy, "workingCopyPolicy");
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    state = Objects.requireNonNull(state, "state");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
