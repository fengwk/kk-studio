/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-environment 的 Environment Capability SPI 和 wire 协议。它维护可重连连接与 invocation
 * journal，但不将连接作为执行事实源，且不得反向依赖 Model、Agent、Tool 或 Runtime。
 *
 * <p>Daemon CLI 仅使用 {@code --gateway-uri} 与 {@code --registration-token}；Daemon 不接收 environment
 * name 或 id，握手阶段将 registrationToken 发送给 Gateway，由服务端根据 PostgreSQL 注册表映射到对应的 Environment Card。
 */
package fun.fengwk.kkstudio.harness.daemon;
