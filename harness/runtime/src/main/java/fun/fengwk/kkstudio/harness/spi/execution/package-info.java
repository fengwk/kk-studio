/**
 * 本地执行的物理生命周期端口：Builtin Driver 与 Tool Processor 唯一使用的执行登记、物理句柄、取消、退出证明、排空与 上一进程恢复边界。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle} 负责在允许本地 body
 * 启动前持久声明持有该执行， 并在物理 body 与本地资源都结束后才写入退出证明。Work 准入、lease 所有权与 supersede 仍由 {@link
 * fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard} 负责：取消、失权与从准入表移除都不等于物理退出，也不释放物理句柄。
 *
 * <p>REMOTE 执行不占用本地物理生命周期，只有本地 DRIVER/TOOL 通过 {@link
 * fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity} 登记。
 */
package fun.fengwk.kkstudio.harness.spi.execution;
