package fun.fengwk.kkstudio.harness.runtime.session;

/**
 * Assistant 声明的已完成工具调用；rendererKey 是冻结的前端渲染身份。
 *
 * <p>{@code historyAction} 是 Provider 响应持久化时由 Tool 拥有者冻结的自然语言动作（可空），{@code environmentName}
 * 是该调用冻结时的 Environment 名（可空）。两者共同决定该调用在后续 Provider 请求中是否仍能保持 native：branch 切换 Environment 后，既有
 * environment-bound 调用必须降级，而 Tool 已被移除 / 定义已变化时 {@code historyAction} 仍可作为唯一 durable 语义来源。
 */
public record ToolCallMessageContent(
    String toolCallId,
    String toolName,
    String rendererKey,
    String argumentsJson,
    String historyAction,
    String environmentName)
    implements AgentMessageContent {

  public ToolCallMessageContent {
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    toolName = requireNonBlank(toolName, "toolName");
    rendererKey = requireNonBlank(rendererKey, "rendererKey");
    argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
    historyAction = requireNullOrNonBlank(historyAction, "historyAction");
    environmentName = requireNullOrNonBlank(environmentName, "environmentName");
  }

  /** 不携带冻结 action / Environment 名的调用内容（无附加历史投影元数据时使用）。 */
  public ToolCallMessageContent(
      String toolCallId, String toolName, String rendererKey, String argumentsJson) {
    this(toolCallId, toolName, rendererKey, argumentsJson, null, null);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requireNullOrNonBlank(String value, String name) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(name + " must be null or non-blank");
    }
    return value;
  }
}
