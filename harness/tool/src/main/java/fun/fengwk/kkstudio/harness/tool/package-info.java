/**
 * Tool 描述、参数 schema、执行 SPI、RemoteTool 与 Daemon wire 公共契约。
 *
 * <p>Descriptor 是 route-neutral 的功能描述。本包不依赖 Agent、Session、Runtime、Spring 或持久化框架。工具执行的持久化、权限与调度由
 * Runtime Tool processor 与 gateway 负责。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.tool.EnvironmentName} 是跨 Runtime/daemon/gateway 共享的
 * Environment 逻辑路由身份：bounded 小写路由名称（无空白、无 {@code '/'}）是进入 durable 协议的唯一身份。
 */
package fun.fengwk.kkstudio.harness.tool;
