package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.Objects;

/** 单次 Model invocation 冻结的工具描述、类型与执行目标。 */
public record ToolBinding(ToolDescriptor descriptor, ToolType type, String environmentName) {

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    type = Objects.requireNonNull(type, "type");
    if (descriptor.type() != type) {
      throw new IllegalArgumentException("tool binding type does not match descriptor");
    }
    if (descriptor.name().length() > 128 || descriptor.version().length() > 128) {
      throw new IllegalArgumentException(
          "tool name and version must fit persistent binding columns");
    }
    if (type == ToolType.PLATFORM && environmentName != null) {
      throw new IllegalArgumentException("PLATFORM binding must not have an environment target");
    }
    if (type == ToolType.ENVIRONMENT && environmentName == null) {
      throw new IllegalArgumentException("ENVIRONMENT binding requires an environment target");
    }
    if (environmentName != null) {
      if (environmentName.isBlank()) {
        throw new IllegalArgumentException("environmentName must not be blank");
      }
      if (!environmentName.equals(environmentName.trim())) {
        throw new IllegalArgumentException(
            "environmentName must not contain surrounding whitespace");
      }
      if (environmentName.length() > 128) {
        throw new IllegalArgumentException("environmentName must fit persistent column bounds");
      }
    }
  }

  /** Platform binding. */
  public static ToolBinding of(ToolDescriptor descriptor) {
    return new ToolBinding(descriptor, ToolType.PLATFORM, null);
  }

  /** Environment binding with an immutable target name. */
  public static ToolBinding of(ToolDescriptor descriptor, String environmentName) {
    return new ToolBinding(descriptor, ToolType.ENVIRONMENT, environmentName);
  }

  public static ToolBinding of(ToolDescriptor descriptor, ToolType type, String environmentName) {
    return new ToolBinding(descriptor, type, environmentName);
  }
}
