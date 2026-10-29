/**
 * Provider 调用契约。
 *
 * <p>Provider 只接收本模块的请求与消息模型，并通过可取消流返回 Provider 无关的事件。SDK 适配实现位于同模块的 {@code
 * fun.fengwk.kkstudio.harness.provider}，不得向调用方泄漏 SDK 类型。窄工厂边界见同包的 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter}。
 *
 * <p>Context-pressure 判定边界：不可变 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ContextPressureFacts} 与纯函数 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ContextPressureDetector} 定义 Provider 错误/生成响应是否体现
 * context wall；platform 的 extractor 只负责把 SDK 异常抽成 facts。
 */
package fun.fengwk.kkstudio.harness.model.provider;
