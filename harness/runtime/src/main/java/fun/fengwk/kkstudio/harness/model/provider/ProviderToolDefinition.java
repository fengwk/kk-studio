package fun.fengwk.kkstudio.harness.model.provider;

/**
 * 发送给 Provider 的工具声明。
 *
 * <p>inputSchemaJson 是由 Agent 从 harness-tool schema 转换出的 Provider 无关 JSON object，避免 model 反向依赖
 * tool。
 */
public record ProviderToolDefinition(String name, String description, String inputSchemaJson) {

  public ProviderToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
      throw new IllegalArgumentException("inputSchemaJson must not be blank");
    }
  }
}
