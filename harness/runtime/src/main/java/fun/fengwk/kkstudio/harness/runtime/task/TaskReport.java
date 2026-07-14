package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic terminal child summary returned as the task ToolResult. */
public record TaskReport(
    long childSessionId,
    long childRunId,
    TaskState terminalState,
    String finalAssistantReport,
    List<ArtifactRef> artifacts,
    int turnCount,
    int toolCount,
    String workspaceRevision) {
  public TaskReport {
    if (childSessionId <= 0 || childRunId <= 0) {
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
  }

  public boolean success() {
    return terminalState == TaskState.SUCCEEDED;
  }

  public String render() {
    StringBuilder output = new StringBuilder();
    output.append("<task id=\"").append(childSessionId).append("\" state=\"")
        .append(terminalState.name().toLowerCase(Locale.ROOT)).append("\">\n")
        .append("<child_run id=\"").append(childRunId).append("\" turns=\"")
        .append(turnCount).append("\" tool_calls=\"").append(toolCount).append("\"/>\n");
    if (success()) {
      output.append("<task_result>").append(finalAssistantReport).append("</task_result>\n");
    } else {
      output.append("<task_error>").append(finalAssistantReport).append("</task_error>\n");
    }
    if (!artifacts.isEmpty()) {
      output.append("<artifacts>");
      for (ArtifactRef artifact : artifacts) {
        output.append("<artifact id=\"").append(artifact.artifactId()).append("\" media_type=\"")
            .append(artifact.mediaType()).append("\" size=\"").append(artifact.sizeBytes())
            .append("\"/>");
      }
      output.append("</artifacts>\n");
    }
    if (workspaceRevision != null) {
      output.append("<workspace_revision>").append(workspaceRevision)
          .append("</workspace_revision>\n");
    }
    return output.append("</task>").toString();
  }
}
