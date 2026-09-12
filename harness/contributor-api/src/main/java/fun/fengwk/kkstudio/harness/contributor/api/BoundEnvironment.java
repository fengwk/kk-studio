package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;

/** 供需要 Environment 绑定的 Tool 执行期访问的窄接口。 */
public interface BoundEnvironment {

  /** 返回当前 Thread/Invocation 冻结的 Environment 路由身份。 */
  EnvironmentId environmentId();

  /** 执行绑定的 Capability；此能力由外部实现提供。 */
  ToolExecutionHandle execute(
      EnvironmentCapabilityDescriptor capability,
      ToolExecutionRequest request,
      ToolExecutionListener listener);
}
