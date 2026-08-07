/**
 * 一次 Tool invocation 的 durable 当前状态及其冻结的请求事实。
 *
 * <p>本包中的类型是作为 {@code harness_tool_invocation} 当前状态持久化的普通 durable value/state 值； 它们不是事件、不是 Event
 * Sourcing 日志、也不是 repository 聚合。请求冻结了 ToolCall 参数与实际 Environment 路由；approval 决策属于 invocation
 * 状态的一部分，不单独建 approval 表。scheduling lease 和 wake fencing 由 {@link
 * fun.fengwk.kkstudio.harness.runtime.work} 包独占拥有。
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.tool;
