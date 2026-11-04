/**
 * Tool invocation 的公开值类型：durable 当前状态、冻结 binding、审批与终态事实。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.tool.ToolInvocation} 是作为 {@code
 * harness_tool_invocation} 当前状态持久化的 聚合，{@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus} 是它的业务状态；{@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolBinding} 与 {@link
 * fun.fengwk.kkstudio.harness.api.tool.ContributorBinding} 冻结定义与贡献 provenance，{@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolApproval} 与 {@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolInputReceipt} 记录人工介入事实，{@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolEffectBatch} 与 {@link
 * fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError} 是终态输出。
 *
 * <p>本包只承载不变量与纯状态转换，不含执行、调度、编排与编码：wire 编解码在 {@code fun.fengwk.kkstudio.harness.codec}，Contributor
 * 作用域视图在 {@code fun.fengwk.kkstudio.harness.api.history}，随调用传递、绝不持久化的 transient 执行请求属于端口契约，放在
 * {@code fun.fengwk.kkstudio.harness.spi}。
 */
package fun.fengwk.kkstudio.harness.api.tool;
