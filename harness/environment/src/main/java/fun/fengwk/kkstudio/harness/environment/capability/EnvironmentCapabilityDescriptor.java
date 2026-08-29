package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Objects;

/** Environment Capability 的执行描述，不携带模型 Tool 展示或权限字段。 */
public record EnvironmentCapabilityDescriptor(
    EnvironmentCapabilityId id, String version, ToolParamsSchema inputSchema, Duration timeout) {

  public EnvironmentCapabilityDescriptor {
    id = Objects.requireNonNull(id, "id");
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
  }
}
