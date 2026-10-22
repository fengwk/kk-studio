package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.function.Consumer;

/** 供需要 Environment 绑定的 Tool 执行期访问的窄接口。 */
public interface BoundEnvironment {

  /** 返回当前 Thread/Invocation 冻结的 Environment 路由身份。 */
  EnvironmentId environmentId();

  /**
   * 阻塞执行绑定的 Capability 并返回其终态结果；此能力由外部实现提供。
   *
   * <p>发送前确定未执行与发送结果不确定的区分由抛出的 {@code EnvironmentCapability*Exception} 类型承载。调用线程被中断时向远端请求取消并抛出
   * {@link InterruptedException}，不等待远端确认。
   */
  ToolResult execute(
      EnvironmentCapabilityDescriptor capability,
      ToolExecutionRequest request,
      Consumer<ToolResult> progress)
      throws InterruptedException;
}
