/**
 * Tool 描述、参数 schema、执行 SPI、RemoteTool 与 Daemon wire 公共契约。
 *
 * <p>Descriptor 是 route-neutral 的功能描述。本包不依赖 Agent、Session、Runtime、Spring 或持久化框架。工具执行的持久化、权限与调度由
 * Runtime {@code ToolWorker} 负责。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.tool.EnvironmentId} 是跨 Runtime/daemon/gateway 共享的
 * Environment route identity：只有 canonical 小写 UUID 文本进入 durable 协议，display name 不参与。
 */
package fun.fengwk.kkstudio.harness.tool;
