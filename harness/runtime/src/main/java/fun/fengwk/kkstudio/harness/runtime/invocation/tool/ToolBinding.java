package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：descriptor、产品类型以及实际 Environment 路由。
 *
 * <p>PLATFORM tool 不得携带 {@code environmentId}；ENVIRONMENT tool 必须恰好携带一个。创建 approval 的 YOLO policy
 * 在此被刻意省略。
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
