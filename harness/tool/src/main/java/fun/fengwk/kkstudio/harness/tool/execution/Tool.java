package fun.fengwk.kkstudio.harness.tool.execution;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

/** 工具实现 SPI；Runtime 负责权限、持久化和执行调度。 */
public interface Tool {

  /** 返回该工具对模型和执行器公开的描述。 */
  ToolDescriptor descriptor();

  /** 启动执行并返回可取消句柄。 */
  ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
}
