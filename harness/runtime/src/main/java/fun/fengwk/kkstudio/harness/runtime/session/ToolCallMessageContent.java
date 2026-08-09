package fun.fengwk.kkstudio.harness.runtime.session;

/** Assistant 声明的已完成工具调用；rendererKey 是冻结的前端渲染身份。 */
public record ToolCallMessageContent(
    String toolCallId, String toolName, String rendererKey, String argumentsJson)
    implements AgentMessageContent {

  public ToolCallMessageContent {
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    toolName = requireNonBlank(toolName, "toolName");
    rendererKey = requireNonBlank(rendererKey, "rendererKey");
    argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
