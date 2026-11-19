/**
 * Prompt Cache 留存意图、协议能力解析与请求级 cache 控制指令。
 *
 * <p>{@link PromptCacheCapabilities} 是以协议、端点和模型标识为键的能力解析事实源；{@link ProviderCacheControl}
 * 则随运行时请求冻结缓存意图与可选只读断点。各协议 Adapter 按该能力映射 wire 字段。
 */
package fun.fengwk.kkstudio.harness.model.cache;
