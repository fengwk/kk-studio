/**
 * provider 包承载模型供应商接入层。
 *
 * <p>边界职责： - 读取 ProviderInfo 连接配置。 - 将 ModelInfo / Variant / ToolInfo 翻译为底层 SDK 请求。 - 屏蔽
 * LangChain4j 供应商差异，向上只暴露统一的 Provider.asyncChat(...) 语义。 - 提取供应商返回的通用与专有 metadata。
 *
 * <p>维护约定： - 新接入 provider 时，优先继承 AbstractModelProvider。 - 底层 SDK 类型只允许停留在本包内，不向 model/tool/session
 * 等领域层泄漏。 - 包内按层次收敛： - 协议层：Provider / AssistantResponse* / ProviderInfo / ProviderType -
 * 注册/工厂层：ProviderRegistry / ProviderManager / ProviderManagerImpl - 通用适配层：AbstractModelProvider -
 * 具体实现层：OpenAiModelProvider / AnthropicModelProvider / GoogleModelProvider /
 * OpenAiResponseModelProvider
 */
package fun.fengwk.kkstudio.agent.provider;
