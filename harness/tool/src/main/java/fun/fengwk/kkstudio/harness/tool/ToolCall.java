package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.InputNormalizer;
import fun.fengwk.kkstudio.harness.common.schema.InputValidator;

/** 已完成的模型工具调用；构造时保留 canonical JSON，执行前通过 {@link #validateFor} 取得归一化后的不可变副本。 */
public record ToolCall(String id, String toolName, String argumentsJson) {

  public ToolCall {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    argumentsJson = JsonValues.requireJsonObject(argumentsJson, "argumentsJson");
  }

  /**
   * 按 descriptor schema 静默归一化参数（数字字符串→integer/number、可缺省属性的显式 null→缺省），再用 schema 严格校验，同时校验工具名称一致。
   *
   * @return 持有归一化后参数的新 {@code ToolCall}；执行路径必须使用该返回值，不得再读取原始 JSON。
   */
  public ToolCall validateFor(ToolDescriptor descriptor) {
    if (!toolName.equals(descriptor.name())) {
      throw new IllegalArgumentException("toolName does not match descriptor");
    }
    String normalized = InputNormalizer.normalize(argumentsJson, descriptor.inputSchema());
    InputValidator.validate(normalized, descriptor.inputSchema());
    if (normalized.equals(argumentsJson)) {
      return this;
    }
    return new ToolCall(id, toolName, normalized);
  }
}
