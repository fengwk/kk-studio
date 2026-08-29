package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

/**
 * 工具实现 SPI；Runtime 负责权限、持久化和执行调度。
 *
 * <p>{@link #execute(ToolExecutionRequest, ToolExecutionListener)} 必须是启动式、快速返回的 async SPI。
 * 允许在调用线程中触发同步 callback，但必须由 Platform 对同步 callback 进行 gate 处理。
 */
public interface Tool {

  /** 返回该工具对模型和执行器公开的描述。 */
  ToolDescriptor descriptor();

  /** 声明工具执行所需的前置依赖与状态访问需求。默认无特殊要求。 */
  default ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  /**
   * 启动执行并返回可取消句柄。
   *
   * <p>必须为启动式、快速返回的 async SPI。允许同步 callback，但由 Platform gate。
   */
  ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
}
