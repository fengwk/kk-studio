/**
 * Builtin 的 MODEL Work 处理器：{@link fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor} 消费
 * Dispatcher 已 claim 的 MODEL Work，由 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelClaimPreparer} 在短事务中复核状态与所有权，再由 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelExecutionLauncher} 经 {@link
 * fun.fengwk.kkstudio.harness.builtin.port.ModelGateway} 准入并启动 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelExecution}。
 *
 * <p>一次 execution 只编排：流式发布、checkpoint 落库与终态提交分别由 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelStreamPublisher}、{@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelCheckpointCommitter} 与 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelTerminalCommitter} 承担；模型组失败的推进事务在 {@link
 * fun.fengwk.kkstudio.harness.builtin.processor.ModelGroupFailureCommitter}。响应校验与规划、结果物化、失败审计快照与重试资格都只在
 * 本模块内实现。
 *
 * <p>进程内执行治理（所有权校验、准入与 execution 登记、租约 margin、取消 / 关闭、活跃执行查询）组合复用 Runtime 公开的 {@link
 * fun.fengwk.kkstudio.harness.spi.work.ClaimedExecutionSupport}，本包不重复实现同构骨架；事务内最终所有权 围栏失败抛唯一的
 * {@link fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal}。历史 Entry 的推进与 Work 请求 / complete 仍由
 * Runtime 掌握。
 */
package fun.fengwk.kkstudio.harness.builtin.processor;
