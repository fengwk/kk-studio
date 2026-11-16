/**
 * Provider 调用契约。
 *
 * <p>Provider 只接收本模块的请求与消息模型，并通过可取消流返回 Provider 无关的事件。SDK 适配实现位于同模块的 {@code
 * fun.fengwk.kkstudio.harness.model.provider.impl}，不得向调用方泄漏 SDK 类型。窄工厂边界见同包的 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter}。
 *
 * <p>Context-pressure 判定边界：纯函数 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ContextPressureDetector} 只基于成功生成响应的 usage、stop reason
 * 与 context window 判断 silent context wall，不解析 Provider 错误文本。
 */
package fun.fengwk.kkstudio.harness.model.provider;
