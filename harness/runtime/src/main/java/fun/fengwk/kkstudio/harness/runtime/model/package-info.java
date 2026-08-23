/**
 * Runtime Model 边界：无状态 Model/Provider 契约与 typed invocation error。
 *
 * <p>顶层 value object 在不依赖 Tool、Session、Runtime 编排、Spring 与持久化的前提下描述一个 model 与其 accounting；子包按
 * codec、Provider 协议与 cache policy 拆分职责。
 *
 * <p>Public 边界与职责划分：
 *
 * <ul>
 *   <li>顶层 value object：{@link fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor}、 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelVariant}、 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelCost}、 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelPricing}、 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelUsage} 与 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality} 描述一个 model、其 modality、其
 *       usage/cost accounting 与 variant。
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.cache}: prompt-cache 的 policy、capability、
 *       mode、retention 与 provider control 值对象。Policy/capability 由调用方显式传入且永不持久化； 只有最终派生的 {@code
 *       ProviderCacheControl} 随请求保存。
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.provider}：所有 SDK adapter 必须使用的 Provider
 *       request、response、message、content-block、stream 与 exception 契约。具体 Provider SDK adapter 实现位于
 *       {@code platform} 模块下的 {@code fun.fengwk.kkstudio.platform.harness.model.provider}，绝不能把 SDK
 *       类型泄漏回本边界。
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.codec} 与 {@link
 *       fun.fengwk.kkstudio.harness.runtime.model.provider.codec}：在 Model/Provider wire 边界使用的严格
 *       deterministic JSON codec。{@link
 *       fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec} 是 {@code
 *       ModelDescriptor}/{@code ModelVariant} 的唯一权威 codec；provider 侧 codec 必须把对应子树委派给它，不得重复实现同一组字段。
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} 承载最小 terminal error
 *       snapshot（kind + 非空 message），使 Runtime 调用方可以在不重读原始 exception 的前提下渲染 transient/permanent 分类。
 *   <li>{@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec} 是持久化
 *       adapter 使用的 {@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError} 严格、确定性
 *       JSON 边界。
 * </ul>
 *
 * <p>durable 的 {@code ModelInvocation} aggregate 与其 codec 位于 {@code
 * harness.runtime.invocation}；状态转换由 {@code harness.runtime.processor} 执行。依赖方向：本包只依赖 Jackson {@code
 * JsonNode}；Spring、JDBC、HTTP 与 SDK 类型不得泄漏到本包或其任何子包。
 */
package fun.fengwk.kkstudio.harness.runtime.model;
