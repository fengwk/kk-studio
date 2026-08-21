package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsNormalizer;
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

  /**
   * 静默归一化参数（如 {@code filePath}→{@code path}、整数字符串→integer），再用 schema 校验，同时校验工具名称一致。
   *
   * @return 持有归一化后参数的新 {@code ToolCall}；执行路径必须使用该返回值，不得再读取原始 JSON。
   */
  public ToolCall validateFor(ToolDescriptor descriptor) {
    if (!toolName.equals(descriptor.name())) {
      throw new IllegalArgumentException("toolName does not match descriptor");
    }
    String normalized = ToolArgumentsNormalizer.normalize(argumentsJson, descriptor.inputSchema());
    ToolArgumentsValidator.validate(normalized, descriptor.inputSchema());
    if (normalized.equals(argumentsJson)) {
      return this;
    }
    return new ToolCall(id, toolName, normalized);
  }
}
