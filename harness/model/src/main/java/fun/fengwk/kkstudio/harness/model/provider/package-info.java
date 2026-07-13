/**
 * Provider 调用契约。
 *
 * <p>Provider 只接收本模块的请求与消息模型，并通过可取消流返回 Provider 无关的事件。 SDK 适配实现放在 {@code provider.adapter}，不得向调用方泄漏
 * SDK 类型。
 */
package fun.fengwk.kkstudio.harness.model.provider;
