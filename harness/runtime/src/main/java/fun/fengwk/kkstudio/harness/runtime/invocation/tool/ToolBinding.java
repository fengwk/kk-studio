package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.Objects;

/**
 * Single Tool invocation frozen binding: descriptor, product type and actual Environment route.
 *
 * <p>PLATFORM tools must not carry an {@code environmentId}; ENVIRONMENT tools must carry exactly
 * one. The YOLO policy that created the approval is deliberately not repeated here.
 */
public record ToolBinding(ToolDescriptor descriptor, ToolType type, EnvironmentId environmentId) {

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    type = Objects.requireNonNull(type, "type");
    if (descriptor.type() != type) {
      throw new IllegalArgumentException("tool binding type does not match descriptor");
    }
    if (type == ToolType.PLATFORM && environmentId != null) {
      throw new IllegalArgumentException("PLATFORM binding must not have an environment route");
    }
    if (type == ToolType.ENVIRONMENT && environmentId == null) {
      throw new IllegalArgumentException("ENVIRONMENT binding requires an environment route");
    }
  }
}
