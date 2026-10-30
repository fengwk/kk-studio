/**
 * Builtin 的 MODEL Work 处理器：{@link fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor} 消费
 * Dispatcher 已 claim 的 MODEL Work，经 {@link fun.fengwk.kkstudio.harness.builtin.port.ModelGateway}
 * 准入后把阻塞执行交给 {@link fun.fengwk.kkstudio.harness.builtin.processor.ModelExecution}
 * 独占虚拟线程；流式节奏、响应校验与规划、结果物化与模型组失败 决策都只在本模块内实现。
 *
 * <p>进程内执行治理（所有权校验、准入与 execution 登记、租约 margin、取消 / 关闭、活跃执行查询）组合复用 Runtime 公开的 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ClaimedExecutionSupport}，本包不重复实现同构骨架；事务内最终所有权
 * 围栏失败抛唯一的 {@link fun.fengwk.kkstudio.harness.runtime.processor.ClaimLostSignal}。历史 Entry 的推进与 Work
 * 请求 / complete 仍由 Runtime 掌握。
 */
package fun.fengwk.kkstudio.harness.builtin.processor;
