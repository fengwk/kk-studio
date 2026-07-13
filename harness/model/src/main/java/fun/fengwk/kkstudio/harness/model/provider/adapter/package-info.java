/**
 * 第三方模型 SDK 适配边界。
 *
 * <p>适配器实现可以依赖 LangChain4j 或其他 Provider SDK，但其公开签名只能使用 {@code
 * fun.fengwk.kkstudio.harness.model.provider} 中的类型。SDK 请求、响应和异常不得跨出此包。
 */
package fun.fengwk.kkstudio.harness.model.provider.adapter;
