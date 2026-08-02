package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Visibility checks for Tool calls returned against one exact Provider request. */
public final class ToolCallVisibility {

  private ToolCallVisibility() {}

  public static List<String> availableToolNames(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    return request.tools().stream().map(ProviderToolDefinition::name).toList();
  }

  public static Optional<ProviderToolCall> firstUnavailable(
      ProviderRequest request, ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    Set<String> available = new HashSet<>(availableToolNames(request));
    return response.toolCalls().stream()
        .filter(call -> !available.contains(call.name()))
        .findFirst();
  }

  public static String unavailableMessage(String toolName, List<String> availableToolNames) {
    Objects.requireNonNull(toolName, "toolName");
    return "tool is not available in this model invocation: "
        + toolName
        + "; available tools: "
        + List.copyOf(Objects.requireNonNull(availableToolNames, "availableToolNames"));
  }
}
