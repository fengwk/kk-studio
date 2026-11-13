package fun.fengwk.kkstudio.harness.api.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;

/**
 * Assistant 声明的已完成工具调用；rendererKey 是冻结的前端渲染身份。
 *
 * <p>{@code historyAction} 是 Provider 响应持久化时由 Tool 拥有者冻结的自然语言动作，可为空。
 */
public record ToolCallMessageContent(
    String toolCallId,
    String toolName,
    String rendererKey,
    String argumentsJson,
    String historyAction,
    ProviderBlockData providerBlockData)
    implements AgentMessageContent {

  public ToolCallMessageContent {
    toolCallId = requireNonBlank(toolCallId, "toolCallId");
    toolName = requireNonBlank(toolName, "toolName");
    rendererKey = requireNonBlank(rendererKey, "rendererKey");
    argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
    historyAction = requireNullOrNonBlank(historyAction, "historyAction");
  }

  public ToolCallMessageContent(
      String toolCallId,
      String toolName,
      String rendererKey,
      String argumentsJson,
      String historyAction) {
    this(toolCallId, toolName, rendererKey, argumentsJson, historyAction, null);
  }

  /** 不携带冻结 action 的调用内容。 */
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
