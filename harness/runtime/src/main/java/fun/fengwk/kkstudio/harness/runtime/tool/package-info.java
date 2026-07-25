/**
 * Durable ToolInvocation 状态、冻结 binding、preparation 与可信 interceptor contract。
 *
 * <p>统一执行状态机位于 {@link fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker}；Platform 与
 * Environment 路由共享同一 worker 和 {@link fun.fengwk.kkstudio.harness.tool.execution.Tool} API。
 */
package fun.fengwk.kkstudio.harness.runtime.tool;
