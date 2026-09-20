package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

import java.time.Duration;
import java.util.Objects;

/**
 * Environment Capability 的执行描述，不携带模型 Tool 展示或权限字段。
 *
 * <p>{@code defaultTimeout} 是该 capability 的唯一默认执行超时；{@link Duration#ZERO} 表示没有执行
 * deadline。调用携带的已解析超时严格覆盖该默认值，两者不合并、不取最小值。
 */
public record EnvironmentCapabilityDescriptor(
    EnvironmentCapabilityId id, String version, InputSchema inputSchema, Duration defaultTimeout) {

  public EnvironmentCapabilityDescriptor {
    id = Objects.requireNonNull(id, "id");
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
    if (defaultTimeout.isNegative()) {
      throw new IllegalArgumentException("defaultTimeout must not be negative");
    }
  }
}
