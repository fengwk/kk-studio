/**
 * 纯 Runtime 协调器与 Thread reconcile 契约。
 *
 * <p>本包是 Session Tree 写者、Head 推进者与 Response debt 协调者。一次 activation 仅收敛 durable facts，不等待任何外部
 * I/O，也不承担 Provider/Tool 执行。所有持久化副作用通过 {@link
 * fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadReconcileTransactions} 端口暴露给 PostgreSQL
 * adapter；本包不引用 Spring、MyBatis、Redis、JSON 或具体 Thread/Model/Tool 行类型之外的业务概念。
 *
 * <p>协作角色严格分离：
 *
 * <ul>
 *   <li>{@link ThreadActivationDispatcher} 只合并本进程的 Thread kick 并提交本地 executor，不决定任何 durable
 *       transition；
 *   <li>{@link ThreadReconciler} 是有界优先级状态机，选择下一项可推进 durable fact；
 *   <li>{@link ThreadReconcileTransactions} 为每个状态转换提供完整的原子用例，并在数据库中执行 lease/epoch/token fencing；
 *   <li>Model、Tool 与 Interaction Worker 仅提交自己的 terminal facts 并触发 best-effort wake；它们不直接写 Session
 *       Tree 或推进 Thread head。
 * </ul>
 *
 * <p>外部依赖仅为 {@code harness.runtime.execution/continuation/thread/session}、{@code
 * harness.runtime.model}（ProviderRequest）与 {@code java.base}。激活循环的可观察不变量：
 *
 * <ul>
 *   <li>每次 mutation 携带 {@link fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadOwnership}
 *       （threadId + executionEpoch + processorToken）作为 fencing identity；
 *   <li>terminal ModelInvocation apply 优先于 terminal Tool sibling apply；
 *   <li>Tool apply 优先于 durable blocker suspend；
 *   <li>durable blocker suspend 优先于既有 response debt 创建 ModelInvocation；
 *   <li>response debt 优先于 TURN_BOUNDARY harvest；
 *   <li>harvest 优先于 quiesce；
 *   <li>消息边界产生的 ModelInvocation 必须先于下一批 Input 创建；
 *   <li>Suspended/Quiescent 及成功创建 ModelInvocation 时 lease 已释放；
 *   <li>suspend 与 quiesce 的 WORK_AVAILABLE 在不释放 lease 的前提下继续收敛；
 *   <li>apply/harvest 仅返回 PROGRESSED/LOST_OWNERSHIP；typed failure 通道仅保留在 ModelInvocation
 *       creation；其余事务的意外 RuntimeException 由适配器向上抛出，Reconciler best-effort release 后重新抛出。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.runtime.reconcile;
