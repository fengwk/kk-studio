/**
 * Work dispatcher：为 THREAD / MODEL / TOOL 的 {@code HarnessStore.Transaction.claimNextWork}
 * 提供首个生产调用方，并把成功 claim 按类型 bounded handoff 给对应 Processor。
 *
 * <p>本包只实现调度生命周期（claim-only 短事务、按类型 round-robin 轮询路由、bounded handoff、合并 wake、 periodic
 * poll、executor rejection 归还 claim、stop 生命周期与 Environment {@code nodeInstanceId} 传递）， 绝不读取 Thread /
 * Invocation 业务状态，也绝不解释 Processor 的 typed 结果改写 durable 状态。
 */
package fun.fengwk.kkstudio.harness.infra.dispatch;
