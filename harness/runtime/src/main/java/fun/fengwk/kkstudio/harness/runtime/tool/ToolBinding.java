package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/** 单 Turn 冻结的工具描述与执行目标绑定；模型只接收其中的 descriptor。 */
public record ToolBinding(
    ToolDescriptor descriptor, ToolTargetType targetType, Long environmentId) {

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
    if (environmentId != null && environmentId <= 0) {
      throw new IllegalArgumentException("environmentId must be positive");
    }
    if (targetType == ToolTargetType.ENVIRONMENT && environmentId == null) {
      throw new IllegalArgumentException("ENVIRONMENT tools require environmentId");
    }
    if (targetType != ToolTargetType.ENVIRONMENT && environmentId != null) {
      throw new IllegalArgumentException("environmentId is only valid for ENVIRONMENT tools");
    }
  }

  public static ToolBinding of(ToolDescriptor descriptor) {
    return new ToolBinding(
        descriptor, ToolTargetType.fromExecutionMode(descriptor.executionMode()), null);
  }
}
