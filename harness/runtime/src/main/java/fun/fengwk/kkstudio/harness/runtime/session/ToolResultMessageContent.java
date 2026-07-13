package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Objects;

/** Tool 消息中单次调用的最终结果。 */
public record ToolResultMessageContent(
    String toolCallId, List<AgentMessageContent> contents, boolean error, String detailsJson)
    implements AgentMessageContent {
  public ToolResultMessageContent {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    if (detailsJson == null || detailsJson.isBlank()) {
      throw new IllegalArgumentException("detailsJson must not be blank");
    }
  }
}
