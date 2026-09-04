/**
 * 内置 Goal 状态机与上下文投影实现。
 *
 * <p>本包提供 branch-scoped 目标管理支持：
 *
 * <ul>
 *   <li>工具支持：{@link fun.fengwk.kkstudio.harness.builtin.goal.CreateGoalTool}、{@link
 *       fun.fengwk.kkstudio.harness.builtin.goal.GetGoalTool} 与 {@link
 *       fun.fengwk.kkstudio.harness.builtin.goal.UpdateGoalTool}；
 *   <li>状态快照与 Codec：不可变全量快照 {@link fun.fengwk.kkstudio.harness.builtin.goal.GoalState} 与严格确定性 JSON
 *       编解码器 {@link fun.fengwk.kkstudio.harness.builtin.goal.GoalStateCodec}；
 *   <li>上下文投影：{@link fun.fengwk.kkstudio.harness.builtin.goal.GoalContextProjector} 仅在最新快照为 {@code
 *       ACTIVE} 时生成 SYSTEM 提示。
 * </ul>
 *
 * <p>关键不变量与边界：
 *
 * <ul>
 *   <li>Goal state 持久化完全依托 {@code harness_entry} 的 CUSTOM payload，entry 维度为 {@code (builtin,
 *       goal.state, 1)}，无独立 Goal 表；
 *   <li>通过 {@link fun.fengwk.kkstudio.harness.contributor.api.BranchView#latestCustomEntry} 仅查询当前
 *       branch 的 root-to-head 路径，sibling branch 严格隔离；
 *   <li>快照为全量替换；{@code ACTIVE} 不含 reason，terminal（{@code COMPLETE} / {@code BLOCKED}）必须含非空
 *       reason；时间戳截断至毫秒且不可回退；
 *   <li>通过声明式的 {@link fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry} effect 交付 Core
 *       在 Tool terminal 时原子追加。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.builtin.goal;
