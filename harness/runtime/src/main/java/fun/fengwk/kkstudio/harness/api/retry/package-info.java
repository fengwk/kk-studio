/**
 * Invocation 自动重试的公开配置值：全局重试预算与模型 HTTP 状态白名单。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy} 承载最大重试次数、{@link
 * fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy} 退避算法（{@code FIXED} / {@code
 * EXPONENTIAL}）与延迟区间，并以纯函数回答是否允许下一次重试、下一次重试前等待多久；{@link
 * fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy} 只对 400–599 的 HTTP
 * 失败回答是否进入重试预算，不是第二个预算。
 *
 * <p>两个值都只做输入校验与纯派生，不读时钟、不持有 durable 状态；一次失败是否真正重试由 {@code runtime.retry} 的判定实现结合错误可重试性与已消耗 attempt
 * 决定，部署配置通过 JDK {@code Supplier}/{@code Function} 在每个判定点现读。
 */
package fun.fengwk.kkstudio.harness.api.retry;
