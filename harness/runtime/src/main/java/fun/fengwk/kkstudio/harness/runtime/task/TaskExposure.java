package fun.fengwk.kkstudio.harness.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Pure helper for T15 context wiring: task is exposed only for an eligible frozen snapshot. */
public final class TaskExposure {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TaskExposure() {}

  public static Optional<ToolDescriptor> descriptor(
      AgentSnapshot snapshot, int currentDepth, ToolDescriptor taskDescriptor) {
    Objects.requireNonNull(taskDescriptor, "taskDescriptor");
    return eligible(snapshot, currentDepth) ? Optional.of(taskDescriptor) : Optional.empty();
  }

  public static String availableSubagentsInstruction(AgentSnapshot snapshot, int currentDepth) {
    if (!eligible(snapshot, currentDepth)) {
      return "";
    }
    List<String> names = snapshot.allowedSubagents().stream().sorted().toList();
    return "<available_subagents>\n"
        + names.stream()
            .map(name -> "  <subagent name=\"" + name + "\"/>")
            .collect(Collectors.joining("\n"))
        + "\n</available_subagents>";
  }

  private static boolean eligible(AgentSnapshot snapshot, int currentDepth) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (currentDepth < 0) {
      throw new IllegalArgumentException("currentDepth must not be negative");
    }
    return !snapshot.allowedSubagents().isEmpty() && currentDepth < maxDepth(snapshot);
  }

  private static int maxDepth(AgentSnapshot snapshot) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(snapshot.executionPolicyJson()).path("maxDepth");
      if (value.isMissingNode() || value.isNull()) {
        return TaskPolicy.DEFAULT_MAX_DEPTH;
      }
      if (!value.canConvertToInt() || value.intValue() <= 0) {
        throw new IllegalArgumentException("execution policy maxDepth must be positive");
      }
      return value.intValue();
    } catch (IOException error) {
      throw new IllegalArgumentException("execution policy must be valid JSON", error);
    }
  }
}
