package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;

import java.util.Objects;

/**
 * 单 Turn 冻结的工具描述与执行目标绑定；模型只接收其中的 descriptor。
 *
 * <p>执行位置由 {@link #location} 拥有，与 location-neutral 的 {@link ToolDescriptor} 分离。PLATFORM 绑定 {@link
 * #of(ToolDescriptor)} 即可，ENVIRONMENT 必须显式提供非空白 {@code environmentName}。
 */
public record ToolBinding(
    ToolDescriptor descriptor, ToolExecutionLocation location, String environmentName) {

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    location = Objects.requireNonNull(location, "location");
    if (descriptor.name().length() > 128 || descriptor.version().length() > 128) {
      throw new IllegalArgumentException(
          "tool name and version must fit persistent binding columns");
    }
    switch (location) {
      case ENVIRONMENT -> {
        if (environmentName == null || environmentName.isBlank()) {
          throw new IllegalArgumentException("ENVIRONMENT tools require environmentName");
        }
        if (environmentName.length() > 128) {
          throw new IllegalArgumentException("environmentName must fit persistent column bounds");
        }
      }
      case PLATFORM -> {
        if (environmentName != null) {
          throw new IllegalArgumentException("environmentName is only valid for ENVIRONMENT tools");
        }
      }
    }
  }

  /** PLATFORM 绑定的便捷构造器；不允许提供 environmentName。 */
  public static ToolBinding of(ToolDescriptor descriptor) {
    return new ToolBinding(descriptor, ToolExecutionLocation.PLATFORM, null);
  }

  /** ENVIRONMENT 绑定的便捷构造器；要求 environmentName 非空白且 <=128。 */
  public static ToolBinding of(ToolDescriptor descriptor, String environmentName) {
    return new ToolBinding(descriptor, ToolExecutionLocation.ENVIRONMENT, environmentName);
  }
}
