package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Pure helper for T15 context wiring: task is exposed only for an eligible frozen snapshot. */
public final class TaskExposure {
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
            .map(name -> "  <subagent name=\"" + escapeXml(name) + "\"/>")
            .collect(Collectors.joining("\n"))
        + "\n</available_subagents>";
  }

  private static boolean eligible(AgentSnapshot snapshot, int currentDepth) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (currentDepth < 0) {
      throw new IllegalArgumentException("currentDepth must not be negative");
    }
    return !snapshot.allowedSubagents().isEmpty()
        && currentDepth < TaskPolicyCodec.decode(snapshot.executionPolicyJson()).maxDepth();
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
