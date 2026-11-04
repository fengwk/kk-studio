/**
 * Invocation 重试策略现读端口。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.spi.retry.InvocationRetryPolicyProvider} 与 {@link
 * fun.fengwk.kkstudio.harness.spi.retry.ModelHttpErrorPolicyProvider} 分别提供重试预算与模型 HTTP
 * 状态白名单的现读快照；策略值属于 {@code fun.fengwk.kkstudio.harness.api.retry}，判定实现属于 {@code
 * fun.fengwk.kkstudio.harness.runtime.retry}。端口只在 retry 判定点读取，不重置 attempt、不改写已派发请求。
 */
package fun.fengwk.kkstudio.harness.spi.retry;
