package fun.fengwk.kkstudio.harness.runtime.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the one structured report consistently for Tool content and ToolResult detailsJson. */
public final class TaskResultFormatter {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TaskResultFormatter() {}

  public static String json(TaskReport report) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childSessionId", Long.toString(report.childSessionId()));
    fields.put("childRunId", Long.toString(report.childRunId()));
    fields.put("status", report.terminalState().name());
    fields.put("finalReport", report.finalAssistantReport());
    fields.put("artifacts", artifacts(report.artifacts()));
    fields.put("turnCount", report.turnCount());
    fields.put("toolCount", report.toolCount());
    fields.put("workingCopyPolicy", report.workingCopyPolicy().name());
    if (report.workingCopyRevision() != null) {
      fields.put("workingCopyRevision", report.workingCopyRevision());
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(fields);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot render task report", error);
    }
  }

  public static String text(TaskReport report) {
    String resultTag = report.success() ? "task_result" : "task_error";
    return "<task id=\""
        + report.childSessionId()
        + "\" state=\""
        + report.terminalState().name().toLowerCase(Locale.ROOT)
        + "\">\n<child_run id=\""
        + report.childRunId()
        + "\" turns=\""
        + report.turnCount()
        + "\" tool_calls=\""
        + report.toolCount()
        + "\" working_copy_policy=\""
        + report.workingCopyPolicy().name()
        + "\"/>\n<"
        + resultTag
        + ">"
        + escapeXml(report.finalAssistantReport())
        + "</"
        + resultTag
        + ">\n</task>";
  }

  private static List<Map<String, Object>> artifacts(List<ArtifactRef> refs) {
    return refs.stream()
        .map(
            ref ->
                Map.<String, Object>of(
                    "artifactId", ref.artifactId(),
                    "mediaType", ref.mediaType(),
                    "sizeBytes", ref.sizeBytes()))
        .toList();
  }

  private static String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
