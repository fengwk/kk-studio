package fun.fengwk.kkstudio.harness.model.provider;

/** Provider 流中的工具调用表示；Agent 会将其转换为 harness-tool 的 ToolCall。 */
public record ProviderToolCall(String id, String name, String argumentsJson) {

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
  }
}
