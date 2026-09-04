/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-environment 的 Environment Capability SPI 和 wire 协议。它维护可重连连接与 Invocation
 * journal，以 journal 作为进程内执行事实源，连接仅作为消息传输管道；Daemon 进程严禁反向依赖 Model、Agent、Tool 或 Runtime。
 *
 * <p>Daemon CLI 仅使用 {@code --gateway-uri} 与 {@code --registration-token} 等参数；Daemon 不接收 Environment
 * name 或 id，握手阶段将 registrationToken 发送给 Gateway，由服务端根据注册表映射到对应的 Environment。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.DaemonRuntime} 统辖双 executor 资源（单线程 scheduler
 * 负责心跳、重连与超时； virtual-thread-per-task executor 负责阻塞执行）、连接 generation 隔离与重试退避，并在运行时冻结 Capability
 * 注册表； {@link fun.fengwk.kkstudio.harness.daemon.InvocationRequestNormalizer} 强制执行工作区真实路径解析与超时规约。
 */
package fun.fengwk.kkstudio.harness.daemon;
