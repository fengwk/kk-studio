package fun.fengwk.kkstudio.harness.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Pure helper for task tool exposure based on current runtime config. */
public final class TaskExposure {
  private TaskExposure() {}

  public static Optional<ToolDescriptor> descriptor(
      AgentRuntimeConfig config, int currentDepth, ToolDescriptor taskDescriptor) {
    Objects.requireNonNull(taskDescriptor, "taskDescriptor");
    return eligible(config, currentDepth) ? Optional.of(taskDescriptor) : Optional.empty();
  }

  public static String availableSubagentsInstruction(AgentRuntimeConfig config, int currentDepth) {
    if (!eligible(config, currentDepth)) {
      return "";
    }
    List<String> names = config.allowedSubagents().stream().sorted().toList();
    return "<available_subagents>\n"
        + names.stream()
            .map(name -> "  <subagent name=\"" + escapeXml(name) + "\"/>")
            .collect(Collectors.joining("\n"))
        + "\n</available_subagents>";
  }

  private static boolean eligible(AgentRuntimeConfig config, int currentDepth) {
    Objects.requireNonNull(config, "config");
    if (currentDepth < 0) {
      throw new IllegalArgumentException("currentDepth must not be negative");
    }
    return !config.allowedSubagents().isEmpty()
        && currentDepth < TaskPolicyCodec.decode(config.executionPolicyJson()).maxDepth();
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
