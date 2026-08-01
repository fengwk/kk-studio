package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/** 单次 Model invocation 冻结的工具描述与可选 Environment 执行目标。 */
public record ToolBinding(ToolDescriptor descriptor, String environmentName) {

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.name().length() > 128 || descriptor.version().length() > 128) {
      throw new IllegalArgumentException(
          "tool name and version must fit persistent binding columns");
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

  /** Platform/local binding. */
  public static ToolBinding of(ToolDescriptor descriptor) {
    return new ToolBinding(descriptor, null);
  }

  /** Environment binding with an immutable target name. */
  public static ToolBinding of(ToolDescriptor descriptor, String environmentName) {
    return new ToolBinding(descriptor, environmentName);
  }
}
