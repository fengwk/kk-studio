package fun.fengwk.kkstudio.harness.runtime.model.provider;

/**
 * Provider 流中的工具调用表示；Agent 会将其转换为 harness-tool 的 ToolCall。
 *
 * <p>{@code historyAction} 是成功响应持久化之前冻结的、Tool-owned 的自然语言动作摘要（可空）。它只在 Provider 无法承载原生长历史时替代
 * 该调用；null 表示没有 Tool 提供的语义映射，投影必须回退到通用描述。该字段绝不影响 Provider 协议编码。
 */
public record ProviderToolCall(String id, String name, String argumentsJson, String historyAction) {

  public ProviderToolCall {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    if (historyAction != null && historyAction.isBlank()) {
      throw new IllegalArgumentException("historyAction must be null or non-blank");
    }
  }

  /** 不携带冻结 action 的调用（Provider 流解析与协议编码路径使用）。 */
  public ProviderToolCall(String id, String name, String argumentsJson) {
    this(id, name, argumentsJson, null);
  }

  /** 返回携带给定冻结 action 的副本；{@code historyAction} 为 null 时表示无 Tool 语义映射。 */
  public ProviderToolCall withHistoryAction(String value) {
    return new ProviderToolCall(id, name, argumentsJson, value);
  }
}
