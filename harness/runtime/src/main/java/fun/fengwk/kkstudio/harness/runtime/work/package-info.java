/**
 * THREAD / MODEL / TOOL 调度共享的单一 Work mailbox。
 *
 * <p>{@link Work} 是一个 target 的 durable 当前调度状态，并独占拥有 scheduling lease 与 wake fencing： {@code
 * wake_version} 防止 wake 丢失，{@code lease_token}/{@code lease_until} fence 掉过期的 worker。
 * 它不是事件日志、不是任务队列、也不是 repository 聚合；其中不存放 target 业务状态、attempt、result 或 approval。
 */
package fun.fengwk.kkstudio.harness.runtime.work;
