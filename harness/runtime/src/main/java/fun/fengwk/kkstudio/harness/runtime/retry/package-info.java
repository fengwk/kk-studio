/**
 * Model 与 Tool Invocation 共享的确定性重试退避策略。
 *
 * <p>策略只计算是否允许下一次重试及其延迟，不读取时钟、不持有 durable 状态，也不决定具体 Invocation 的 terminal transition。Worker 将结果交给其
 * use-case transaction port 写入 {@code RETRY_WAIT}。
 */
package fun.fengwk.kkstudio.harness.runtime.retry;
