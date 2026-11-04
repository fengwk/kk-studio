/**
 * Thread 邮箱命令的公开值类型：durable command、typed payload 族与派生的生命周期状态。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.command.ThreadCommand} 是已分配 durable sequence 的 command
 * 聚合， {@link fun.fengwk.kkstudio.harness.api.command.NewThreadCommand} 是分配 sequence 之前的入队请求； {@link
 * fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload} 是封闭的 typed payload 族， {@link
 * fun.fengwk.kkstudio.harness.api.command.ThreadCommandType} 与 {@link
 * fun.fengwk.kkstudio.harness.api.command.ThreadCommandState} 分别是它的 discriminator 与派生状态。
 *
 * <p>本包只承载不变量校验与纯派生，不含编码、持久化与 mailbox 应用逻辑；JSON 形态与 request hash 在 {@code
 * fun.fengwk.kkstudio.harness.codec}，harvest 归约仍在 {@code
 * fun.fengwk.kkstudio.harness.runtime.thread.command}。
 */
package fun.fengwk.kkstudio.harness.api.command;
