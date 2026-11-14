/**
 * 与 Provider 解耦的 Prompt Cache 留存档位与请求级 cache 控制指令。
 *
 * <p>只保留 {@link fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention} 与不可变的 {@link
 * fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl}：后者随请求冻结，携带留存档位与可选的只读断点下标。到各
 * Provider 协议的具体 cache 参数映射由 Adapter 完成。
 */
package fun.fengwk.kkstudio.harness.model.cache;
