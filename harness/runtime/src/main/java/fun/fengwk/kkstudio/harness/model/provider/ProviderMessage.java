package fun.fengwk.kkstudio.harness.model.provider;

import java.util.List;
import java.util.Objects;

/** 已完成语义消息到 Provider 请求消息的无损、Provider 无关表示。 */
public record ProviderMessage(ProviderMessageRole role, List<ProviderContentBlock> contents) {

  public ProviderMessage {
    role = Objects.requireNonNull(role, "role");
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    validateRoleContents(role, contents);
  }

  private static void validateRoleContents(
      ProviderMessageRole role, List<ProviderContentBlock> contents) {
    boolean hasToolCall = contents.stream().anyMatch(ProviderToolCallBlock.class::isInstance);
    boolean hasToolResult = contents.stream().anyMatch(ProviderToolResultBlock.class::isInstance);
    if (hasToolCall && role != ProviderMessageRole.ASSISTANT) {
      throw new IllegalArgumentException(
          "tool call blocks are only allowed for ASSISTANT messages");
    }
    if (hasToolResult && role != ProviderMessageRole.TOOL) {
      throw new IllegalArgumentException("tool result blocks are only allowed for TOOL messages");
    }
    if (role == ProviderMessageRole.TOOL
        && (contents.size() != 1 || !(contents.get(0) instanceof ProviderToolResultBlock))) {
      throw new IllegalArgumentException(
          "TOOL messages must contain exactly one provider tool result block");
    }
  }
}
