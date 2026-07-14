package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Objects;

/** Current durable observation of a parent task invocation. */
public record TaskInspection(SubagentTask task, TaskReport report) {
  public TaskInspection {
    task = Objects.requireNonNull(task, "task");
    if (task.state().terminal() != (report != null)) {
      throw new IllegalArgumentException("terminal task must have exactly one report");
    }
  }

  public boolean terminal() {
    return task.state().terminal();
  }
}
