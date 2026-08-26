package fun.fengwk.kkstudio.harness.tool.capability;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsNormalizer;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

import java.util.Objects;

/**
 * 一次 Environment Capability 调用。
 *
 * <p>{@code id} 是本次调用的 correlation id，不是 Capability 身份；Capability 身份由执行请求中的 descriptor 提供。
 */
public record EnvironmentCapabilityCall(String id, String argumentsJson) {

  public EnvironmentCapabilityCall {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    argumentsJson = ToolArgumentsValidator.requireJsonObject(argumentsJson);
  }

  /**
   * 静默归一化并校验参数，返回持有归一化 JSON 的调用。
   *
   * @return 归一化后的新调用，或参数未改变时的当前调用
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
}
