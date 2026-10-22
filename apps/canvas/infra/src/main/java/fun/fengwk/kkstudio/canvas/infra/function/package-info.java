/**
 * Canvas Function Runtime 基座：冻结 catalog、短事务状态机、进程内 dispatcher/worker 与恢复装配。
 *
 * <p>本包只依赖 Canvas Core 端口；Blob 与 Resource 生命周期由生产组合根提供外层实现，第三方 adapter 留在 Platform。
 */
package fun.fengwk.kkstudio.canvas.infra.function;
