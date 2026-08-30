package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.InputNormalizer;
import fun.fengwk.kkstudio.harness.common.schema.InputValidator;

import java.util.Objects;

/**
 * 结构化的 Environment Capability 调用请求。
 *
 * <p>调用 ID 拥有非空/非空白要求；入参 JSON 在构造期校验为合法 JSON 对象。
 */
public record EnvironmentCapabilityCall(String id, String argumentsJson) {

  public EnvironmentCapabilityCall {
    id = requireNonBlank(id, "id");
    argumentsJson = JsonValues.requireJsonObject(argumentsJson, "argumentsJson");
  }

  /**
   * 静默归一化参数（如 {@code filePath}→{@code path}、整数字符串→integer），再用 schema 校验。
   *
   * @return 持有归一化后参数的新 {@code EnvironmentCapabilityCall}。
   */
  public EnvironmentCapabilityCall validateFor(EnvironmentCapabilityDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    String normalized = InputNormalizer.normalize(argumentsJson, descriptor.inputSchema());
    InputValidator.validate(normalized, descriptor.inputSchema());
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
