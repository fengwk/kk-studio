package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;

import java.time.Instant;

/** Durable task command port. Implementations own transactional locking and recovery. */
public interface TaskRuntime {
  /**
   * Creates a child attempt, or idempotently replays the durable relation for the same parent
   * invocation.
   */
  TaskInspection startOrResume(ToolExecutionContext context, TaskCommand command, Instant now);

  /** Inspects one child attempt against durable lifecycle facts. */
  TaskInspection inspect(long parentInvocationId, Instant now);

  /** Idempotently requests cancellation for the complete descendant tree. */
  void cancelTree(long parentInvocationId, Instant now);
}
