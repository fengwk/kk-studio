package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsNormalizer;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

import java.util.Objects;

/**
 * 结构化的 Environment Capability 调用请求。
 *
 * <p>调用 ID 与模型 ToolCall 拥有相同的非空/非空白要求；入参 JSON 在构造期校验为合法 JSON。
 */
public record EnvironmentCapabilityCall(String id, String argumentsJson) {

  public EnvironmentCapabilityCall {
    id = requireNonBlank(id, "id");
    argumentsJson = ToolArgumentsValidator.requireJsonObject(argumentsJson);
  }

  /**
   * 静默归一化参数（如 {@code filePath}→{@code path}、整数字符串→integer），再用 schema 校验。
   *
   * @return 持有归一化后参数的新 {@code EnvironmentCapabilityCall}。
   */
  public EnvironmentCapabilityCall validateFor(EnvironmentCapabilityDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    String normalized = ToolArgumentsNormalizer.normalize(argumentsJson, descriptor.inputSchema());
    ToolArgumentsValidator.validate(normalized, descriptor.inputSchema());
    if (normalized.equals(argumentsJson)) {
      return this;
    }
    return new EnvironmentCapabilityCall(id, normalized);
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}
