package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.util.List;
import java.util.Objects;

/** Deterministic terminal child summary returned as the task ToolResult. */
public record TaskReport(
    long childSessionId,
    long childThreadId,
    TaskState terminalState,
    String finalAssistantReport,
    List<ArtifactRef> artifacts,
    int turnCount,
    int toolCount,
    WorkingCopyPolicy workingCopyPolicy,
    String workingCopyRevision) {
  public TaskReport {
    if (childSessionId <= 0 || childThreadId <= 0) {
      throw new IllegalArgumentException("child ids must be positive");
    }
    terminalState = Objects.requireNonNull(terminalState, "terminalState");
    if (!terminalState.terminal()) {
      throw new IllegalArgumentException("task report requires a terminal state");
    }
    finalAssistantReport = finalAssistantReport == null ? "" : finalAssistantReport;
    artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    if (turnCount < 0 || toolCount < 0) {
      throw new IllegalArgumentException("report counts must not be negative");
    }
    workingCopyPolicy = Objects.requireNonNull(workingCopyPolicy, "workingCopyPolicy");
  }

  public boolean success() {
    return terminalState == TaskState.SUCCEEDED;
  }
}
