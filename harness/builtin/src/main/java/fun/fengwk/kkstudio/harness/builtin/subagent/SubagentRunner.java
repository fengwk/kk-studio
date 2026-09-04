package fun.fengwk.kkstudio.harness.builtin.subagent;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;

/** 执行 Subagent 委派任务的运行时端口。 */
@FunctionalInterface
public interface SubagentRunner {

  /**
   * 启动 Subagent 任务执行。
   *
   * @param taskRequest 解析出的 Subagent 任务参数
   * @param listener 统一结果回调监听器
   * @return 可取消的执行句柄
   */
  ToolExecutionHandle run(SubagentTaskRequest taskRequest, ToolExecutionListener listener);
}
