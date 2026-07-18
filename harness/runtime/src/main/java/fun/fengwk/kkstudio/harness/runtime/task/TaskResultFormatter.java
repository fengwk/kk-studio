package fun.fengwk.kkstudio.harness.runtime.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.util.ArrayList;
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
    fields.put("childThreadId", Long.toString(report.childThreadId()));
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

  /** Strict decode of {@link #json(TaskReport)} payload. */
  public static TaskReport decodeJson(String reportJson) {
    if (reportJson == null || reportJson.isBlank()) {
      throw new IllegalArgumentException("reportJson must not be blank");
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(reportJson);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("reportJson must be a JSON object");
      }
      long childSessionId = requirePositiveDecimalId(node, "childSessionId");
      long childThreadId = requirePositiveDecimalId(node, "childThreadId");
      if (!node.hasNonNull("status") || !node.get("status").isTextual()) {
        throw new IllegalArgumentException("status must be a non-null string");
      }
      TaskState status = TaskState.valueOf(node.get("status").asText());
      if (!status.terminal()) {
        throw new IllegalArgumentException("status must be terminal");
      }
      String finalReport = requireStringOrEmpty(node, "finalReport");
      int turnCount = requireNonNegativeInt(node, "turnCount");
      int toolCount = requireNonNegativeInt(node, "toolCount");
      if (!node.hasNonNull("workingCopyPolicy") || !node.get("workingCopyPolicy").isTextual()) {
        throw new IllegalArgumentException("workingCopyPolicy must be a non-null string");
      }
      WorkingCopyPolicy policy = WorkingCopyPolicy.valueOf(node.get("workingCopyPolicy").asText());
      String revision = null;
      if (node.has("workingCopyRevision") && !node.get("workingCopyRevision").isNull()) {
        if (!node.get("workingCopyRevision").isTextual()) {
          throw new IllegalArgumentException("workingCopyRevision must be a string when present");
        }
        revision = node.get("workingCopyRevision").asText();
      }
      if (!node.has("artifacts") || !node.get("artifacts").isArray()) {
        throw new IllegalArgumentException("artifacts must be an array");
      }
      List<ArtifactRef> artifactRefs = new ArrayList<>();
      for (JsonNode item : node.get("artifacts")) {
        if (!item.isObject()) {
          throw new IllegalArgumentException("artifact entries must be objects");
        }
        artifactRefs.add(requireArtifact(item));
      }
      return new TaskReport(
          childSessionId,
          childThreadId,
          status,
          finalReport,
          artifactRefs,
          turnCount,
          toolCount,
          policy,
          revision);
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (RuntimeException | JsonProcessingException error) {
      throw new IllegalArgumentException("cannot decode task report json", error);
    }
  }

  private static long requirePositiveDecimalId(JsonNode node, String field) {
    if (!node.hasNonNull(field) || !node.get(field).isTextual()) {
      throw new IllegalArgumentException(field + " must be a decimal string");
    }
    String text = node.get(field).asText();
    if (text.isBlank() || !text.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException(field + " must be a positive decimal string");
    }
    long value = Long.parseLong(text);
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static int requireNonNegativeInt(JsonNode node, String field) {
    if (!node.has(field) || node.get(field).isNull() || !node.get(field).isIntegralNumber()) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    int value = node.get(field).asInt();
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return value;
  }

  private static String requireStringOrEmpty(JsonNode node, String field) {
    if (!node.has(field) || node.get(field).isNull()) {
      return "";
    }
    if (!node.get(field).isTextual()) {
      throw new IllegalArgumentException(field + " must be a string");
    }
    return node.get(field).asText();
  }

  private static ArtifactRef requireArtifact(JsonNode item) {
    if (!item.hasNonNull("artifactId") || !item.get("artifactId").isTextual()) {
      throw new IllegalArgumentException("artifactId must be non-blank text");
    }
    if (!item.hasNonNull("mediaType") || !item.get("mediaType").isTextual()) {
      throw new IllegalArgumentException("mediaType must be non-blank text");
    }
    String artifactId = item.get("artifactId").asText();
    String mediaType = item.get("mediaType").asText();
    if (artifactId.isBlank() || mediaType.isBlank()) {
      throw new IllegalArgumentException("artifactId/mediaType must be non-blank");
    }
    if (!item.has("sizeBytes")
        || item.get("sizeBytes").isNull()
        || !item.get("sizeBytes").isIntegralNumber()) {
      throw new IllegalArgumentException("sizeBytes must be a non-negative integer");
    }
    long sizeBytes = item.get("sizeBytes").asLong();
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
    return new ArtifactRef(artifactId, mediaType, sizeBytes);
  }

  public static String text(TaskReport report) {
    String resultTag = report.success() ? "task_result" : "task_error";
    return "<task id=\""
        + report.childSessionId()
        + "\" state=\""
        + report.terminalState().name().toLowerCase(Locale.ROOT)
        + "\">\n<child_thread id=\""
        + report.childThreadId()
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
