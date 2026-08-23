/**
 * Work dispatcher：为 THREAD / MODEL / TOOL 的 {@code HarnessStore.Transaction.claimNextWork}
 * 提供首个生产调用方，并把成功 claim 按类型 bounded handoff 给对应 Processor。
 *
 * <p>本包只实现调度生命周期（claim、按类型路由、bounded handoff、合并 wake、periodic poll、stop），绝不读取 Thread / Invocation
 * business state，也绝不解释 Processor 的 typed 结果改写 durable 状态。
 */
package fun.fengwk.kkstudio.harness.infra.dispatch;
