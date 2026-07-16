/**
 * Prompt Cache affinity 派生与 finalizer。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory} 派生稳定 cache
 * key；{@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer} 在 Provider
 * request 链最后覆盖 cacheControl。Worker 在每条 claim Run 创建 engine 时按 sessionId 追加最终 finalizer。
 */
package fun.fengwk.kkstudio.harness.runtime.cache;
