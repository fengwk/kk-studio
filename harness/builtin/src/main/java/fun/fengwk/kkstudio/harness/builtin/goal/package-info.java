/**
 * 内置 Goal 读取与 Agent 进度声明实现。
 *
 * <p>本包提供 branch-scoped 目标的只读与声明能力：
 *
 * <ul>
 *   <li>工具：{@link fun.fengwk.kkstudio.harness.builtin.goal.GetGoalTool} 与 {@link
 *       fun.fengwk.kkstudio.harness.builtin.goal.UpdateGoalTool}；
 *   <li>进度声明：不可变快照 {@link fun.fengwk.kkstudio.harness.builtin.goal.GoalProgress} 与严格确定性 JSON 编解码器
 *       {@link fun.fengwk.kkstudio.harness.builtin.goal.GoalProgressCodec}。
 * </ul>
 *
 * <p>关键不变量与边界：
 *
 * <ul>
 *   <li>唯一目标正文由用户在 branch settings（{@code BranchSettings.goal}）维护；Agent 只能通过 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.BranchView#goal()} 读取，没有创建或改写工具，也不把 Goal 提升为
 *       systemInstruction；
 *   <li>Agent 声明持久化依托 {@code harness_entry} 的 CUSTOM payload，entry 维度为 {@code (builtin,
 *       goal.progress, 1)}，只绑定 {@code goalId}，不复制正文、不删除 Goal、不推进业务状态；
 *   <li>用户设置新目标或清除目标后，旧 {@code goalId} 的声明自动失效，不会被当成当前目标的进度；
 *   <li>通过声明式的 {@link fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry} effect 交付 Core
 *       在 Tool terminal 时原子追加。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.builtin.goal;
