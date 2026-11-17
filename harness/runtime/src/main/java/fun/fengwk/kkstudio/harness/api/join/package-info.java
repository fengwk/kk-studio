/**
 * Subagent 委派的公开契约：一次源命令的不可变 join 凭据、结算后的只读结果凭据与其渲染结果。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.join.ThreadJoin} 冻结子 Thread、源 command sequence、目标 Agent
 * 与终止 Entry，并由自身方法单向推进匹配、提醒、交付与 supersede；{@link fun.fengwk.kkstudio.harness.api.join.JoinDelivery}
 * 决定结算后谁被通知，{@link fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome} 是结果凭据的终态。 {@link
 * fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt} 从已匹配的 join 投影调用方可见结果， {@link
 * fun.fengwk.kkstudio.harness.api.join.ThreadJoinCompletionRenderer} 把该凭据渲染为父 Thread 接收的 subagent
 * completion XML 消息。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.join.SubagentTaskRequest} 定义 task Join
 * 的规范参数解析、幂等身份、durable Join 校验与 receipt 结果投影。该协议只依赖持久事实与值类型，不调用 Store、Driver、Tool 或 Work。
 *
 * <p>持久化、入队与交付标记、历史投影都在 runtime 侧实现；本包不接触 Store、Driver 或 Work。
 */
package fun.fengwk.kkstudio.harness.api.join;
