package fun.fengwk.kkstudio.harness.api.session;

import java.util.List;
import java.util.Objects;

/** Tool 消息中单次调用的最终结果；rendererKey 与对应 ToolCall 的冻结渲染身份相同。 */
public record ToolResultMessageContent(
    String toolCallId,
    String toolName,
    String rendererKey,
    List<AgentMessageContent> contents,
    boolean error,
    String detailsJson)
    implements AgentMessageContent {

  public ToolResultMessageContent {
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    toolName = requireNonBlank(toolName, "toolName");
    rendererKey = requireNonBlank(rendererKey, "rendererKey");
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    for (AgentMessageContent content : contents) {
      // 结果内容不能再嵌回调用或结果：嵌套会把一次调用的终态伪装成多层协议事实。
      if (content instanceof ToolCallMessageContent
          || content instanceof ToolResultMessageContent) {
        throw new IllegalArgumentException(
            "tool result contents cannot nest tool call or tool result");
      }
    }
    if (detailsJson == null || detailsJson.isBlank()) {
      throw new IllegalArgumentException("detailsJson must not be blank");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
