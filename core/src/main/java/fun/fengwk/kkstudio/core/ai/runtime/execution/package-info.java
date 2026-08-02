/**
 * PostgreSQL 持久化 ExecutionActivation 基座。
 *
 * <p>本包拥有 harness_execution_activation 表、事务性 NOTIFY 通道以及单线程分发器和监听器。通用存储只处理激活记录的 CRUD、
 * 行锁、状态切换、到期扫描和最近 wakeAt；Environment Tool FIFO 由 EnvironmentToolActivationQueue 独立维护。
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivation} — 不可变激活投影
 *   <li>{@link fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationStore} — 通用持久化端口
 *   <li>{@link fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationHandler} — 单条激活处理端口
 *   <li>{@link
 *       fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationEnvironmentEligibility} —
 *       当前节点 READY Environment 快照
 *   <li>{@link fun.fengwk.kkstudio.core.ai.runtime.execution.EnvironmentToolActivationQueue} —
 *       Environment Tool FIFO 端口
 *   <li>{@link
 *       fun.fengwk.kkstudio.core.ai.runtime.execution.PostgresqlExecutionActivationDispatcher} —
 *       合并唤醒和最近 wakeAt 定时的扫描
 *   <li>{@link fun.fengwk.kkstudio.core.ai.runtime.execution.PostgresqlExecutionActivationListener}
 *       — {@code LISTEN harness_execution_activation} 循环
 * </ul>
 *
 * <p>SCHEDULED 记录参与到期扫描，PARKED 记录只保留在持久化事实中。NOTIFY 仅在 SCHEDULED 插入、PARKED 到 SCHEDULED 的转换以及已
 * SCHEDULED 记录严格提前 wakeAt 时发送。
 */
package fun.fengwk.kkstudio.core.ai.runtime.execution;
