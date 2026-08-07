package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 针对一个精确 Provider request 返回的 Tool call 的可见性检查。 */
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
