/**
 * 与 Provider 解耦的 Prompt Cache 领域值对象、能力协商与缓存控制指令。
 *
 * <p>定义 {@link fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy}、 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability}、 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention} 等无状态值类型； 最终派生为请求携带的不可变
 * {@link fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl} 快照。
 * 本包仅承载静态策略与指令契约，不维护缓存底层存储或命中事实。
 */
package fun.fengwk.kkstudio.harness.runtime.model.cache;
