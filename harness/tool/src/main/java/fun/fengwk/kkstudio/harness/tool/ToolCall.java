package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

/** 已完成的模型工具调用；参数保持原始 JSON 以供 Provider 和 Daemon 传递。 */
public record ToolCall(String id, String toolName, String argumentsJson) {

  public ToolCall {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    argumentsJson = ToolArgumentsValidator.requireJsonObject(argumentsJson);
  }

  /** 验证参数符合该工具的 schema，同时校验工具名称一致。 */
  public void validateFor(ToolDescriptor descriptor) {
    if (!toolName.equals(descriptor.name())) {
      throw new IllegalArgumentException("toolName does not match descriptor");
    }
    ToolArgumentsValidator.validate(argumentsJson, descriptor.inputSchema());
  }
}
