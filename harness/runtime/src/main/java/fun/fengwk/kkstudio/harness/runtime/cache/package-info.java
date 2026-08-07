/**
 * Prompt Cache affinity 派生与 request finalizer。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory} 派生稳定 cache
 * key；{@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer} 在冻结
 * ProviderRequest 时按调用方显式传入的 {@code PromptCachePolicy} 写入最终 cacheControl。执行路径不再暴露可变 interceptor
 * hook。
 */
package fun.fengwk.kkstudio.harness.runtime.cache;
