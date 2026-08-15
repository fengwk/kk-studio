package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：descriptor、产品类型以及完整 Environment binding。
 *
 * <p>PLATFORM tool 不得携带 {@code environment}；ENVIRONMENT tool 携带最新 branch 的 {@code environment}（可为
 * null 或当前不可用——实际执行时确定性失败）。创建 approval 的 YOLO policy 在此被刻意省略。
 */
public record ToolBinding(
    ToolDescriptor descriptor,
    ToolType type,
    EnvironmentBinding environment,
    PluginToolBinding plugin) {

  /** 构造非插件 Tool binding。 */
  public ToolBinding(ToolDescriptor descriptor, ToolType type, EnvironmentBinding environment) {
    this(descriptor, type, environment, null);
  }

  public ToolBinding {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    type = Objects.requireNonNull(type, "type");
    if (descriptor.type() != type) {
      throw new IllegalArgumentException("tool binding type does not match descriptor");
    }
    if (type == ToolType.PLATFORM && environment != null) {
      throw new IllegalArgumentException("PLATFORM binding must not have an environment binding");
    }
    if (type == ToolType.ENVIRONMENT && plugin != null) {
      throw new IllegalArgumentException("ENVIRONMENT binding must not have plugin provenance");
    }
  }
}
