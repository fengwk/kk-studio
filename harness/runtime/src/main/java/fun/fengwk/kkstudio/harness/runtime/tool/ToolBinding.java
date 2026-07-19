package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/** 单 Turn 冻结的工具描述与执行目标绑定；模型只接收其中的 descriptor。 */
public record ToolBinding(
    ToolDescriptor descriptor, ToolTargetType targetType, String environmentName) {

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.name().length() > 128 || descriptor.version().length() > 128) {
      throw new IllegalArgumentException(
          "tool name and version must fit persistent binding columns");
    }
    targetType = Objects.requireNonNull(targetType, "targetType");
    if (targetType != ToolTargetType.fromExecutionMode(descriptor.executionMode())) {
      throw new IllegalArgumentException(
          "binding targetType does not match descriptor executionMode");
    }
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank when present");
    }
    if (environmentName != null && environmentName.length() > 128) {
      throw new IllegalArgumentException("environmentName must fit persistent column bounds");
    }
    if (targetType == ToolTargetType.ENVIRONMENT && environmentName == null) {
      throw new IllegalArgumentException("ENVIRONMENT tools require environmentName");
    }
    if (targetType != ToolTargetType.ENVIRONMENT && environmentName != null) {
      throw new IllegalArgumentException("environmentName is only valid for ENVIRONMENT tools");
    }
  }

  public static ToolBinding of(ToolDescriptor descriptor) {
    return new ToolBinding(
        descriptor, ToolTargetType.fromExecutionMode(descriptor.executionMode()), null);
  }
}
