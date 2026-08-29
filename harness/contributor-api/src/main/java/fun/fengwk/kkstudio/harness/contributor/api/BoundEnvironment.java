package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;

/** 执行上下文中的窄环境能力，不暴露底层 Platform 或 Store。 */
public interface BoundEnvironment {

  /** 返回当前绑定的环境信息。 */
  EnvironmentBinding binding();

  /** 在绑定的环境中执行指定能力。 */
  ToolExecutionHandle execute(
      EnvironmentCapabilityDescriptor capability,
      ToolExecutionRequest request,
      ToolExecutionListener listener);
}
