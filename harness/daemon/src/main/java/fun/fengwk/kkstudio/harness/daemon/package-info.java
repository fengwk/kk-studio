/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 通过 harness-environment 的 Environment Capability SPI 和 wire 协议与 Platform 交互。它维护可重连连接与
 * Invocation journal，以 journal 作为进程内执行事实源，连接仅作为消息传输管道；Daemon 进程严禁反向依赖 Model、Agent、Tool 或 Runtime。
 *
 * <p>Daemon CLI 仅使用 {@code --gateway-uri} 与 {@code --registration-token-file} 等参数；Daemon 不接收
 * Environment name 或 id，握手阶段从凭证文件按需读取 registration token 并发送给 Gateway，由服务端根据注册表映射到对应的
 * Environment。凭证只以 owner-only 普通文件存在，进程参数、环境变量与日志都不携带其文本。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.DaemonRuntime} 统辖双 executor 资源（单线程 scheduler
 * 负责心跳、重连与超时； virtual-thread-per-task executor 负责阻塞执行）、连接 generation 隔离与重试退避，并在运行时冻结 Capability
 * 注册表；coding/process/LSP Capability 从各自 arguments 读取显式 workdir，并在实际执行前校验目标机上的真实目录与超时规约。
 */
package fun.fengwk.kkstudio.harness.daemon;
