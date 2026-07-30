/**
 * Provider 调用契约。
 *
 * <p>Provider 只接收本模块的请求与消息模型，并通过可取消流返回 Provider 无关的事件。 SDK 适配实现位于 core 的 {@code
 * fun.fengwk.kkstudio.core.ai.runtime.model.provider}，不得向调用方泄漏 SDK 类型。 窄工厂边界见同包的 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter}。
 */
package fun.fengwk.kkstudio.harness.runtime.model.provider;
