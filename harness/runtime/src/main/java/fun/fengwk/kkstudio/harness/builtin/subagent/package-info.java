/**
 * 内置 Subagent 委派工具实现与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool} 作为内部（{@code
 * ToolVisibility.INTERNAL}）工具注册， 负责解析模型委派调用的参数契约并转化为 {@link
 * fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest}， 委托 {@link
 * fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner} 在独立的 Subagent Session 与 Thread
 * 中执行任务。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig} 与 {@link
 * fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider} 提供运行期现读的并发与预算参数；{@link
 * fun.fengwk.kkstudio.harness.builtin.subagent.SubagentPrompts} 负责任务相关 prompt 模板加载。
 *
 * <p>本包为委派适配层，不在此处实现多轮调度引擎或持久化状态机。
 */
package fun.fengwk.kkstudio.harness.builtin.subagent;
