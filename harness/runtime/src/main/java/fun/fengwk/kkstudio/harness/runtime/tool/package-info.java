/**
 * Runtime Tool 边界：framework-free Tool factories 与 typed invocation error。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory} 声明普通 Platform Tool 的
 * AgentToolDefinition 与 priority；{@link
 * fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError} 携带稳定 kind 与 非空 message，由 {@link
 * fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec} 做 strict JSON 编解码。Tool
 * invocation 的 durable aggregate 位于 {@code harness.runtime.invocation}，执行由 {@code
 * harness.runtime.processor} 与 {@code harness.runtime.port.ToolGateway} 完成；本包不定义执行状态机。
 */
package fun.fengwk.kkstudio.harness.runtime.tool;
