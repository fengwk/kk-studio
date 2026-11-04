/**
 * Model 与 Tool Invocation 共享的确定性重试判定。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision}
 * 把当前生效的重试预算、错误是否可重试与本次失败已消耗的 attempt 合成唯一决策，不读取时钟、不持有 durable 状态，也不决定具体 Invocation 的 terminal
 * transition。Worker 将结果交给其 use-case transaction port 写入 {@code RETRY_WAIT}。
 *
 * <p>策略配置值（重试预算与模型 HTTP 状态白名单）在 {@code fun.fengwk.kkstudio.harness.api.retry}，部署配置通过 JDK {@code
 * Supplier}/{@code Function} 在判定点现读；本包只保留判定实现。
 */
package fun.fengwk.kkstudio.harness.runtime.retry;
