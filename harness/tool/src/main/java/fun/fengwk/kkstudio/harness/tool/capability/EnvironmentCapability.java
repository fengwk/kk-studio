package fun.fengwk.kkstudio.harness.tool.capability;

/** 独立于模型 Tool 的 Environment Capability 流式执行 SPI。 */
public interface EnvironmentCapability {

  /** 返回该 Capability 的稳定执行描述。 */
  EnvironmentCapabilityDescriptor descriptor();

  /** 启动执行并返回可取消句柄。 */
  EnvironmentCapabilityExecutionHandle execute(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener);
}
