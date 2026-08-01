/**
 * Tool 描述、参数 schema、执行 SPI、RemoteTool 与 Daemon wire 公共契约。
 *
 * <p>Descriptor 是 route-neutral 的功能描述。本包不依赖 Agent、Session、Runtime、Spring 或持久化框架。工具执行的持久化、权限与调度由
 * Runtime {@code ToolWorker} 负责。
 */
package fun.fengwk.kkstudio.harness.tool;
