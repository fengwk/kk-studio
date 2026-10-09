/**
 * Environment 会话服务端核心：纯 Java 的 {@link
 * fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer} 独占
 * HELLO/WELCOME/READY/HEARTBEAT/INVOKE 等 daemon 协议会话状态、租约围栏（{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonLeaseStore}）以及按 invocation 维度的在途调用生命周期。
 *
 * <p>边界与职责：本模块只依赖 JDK、Jackson、harness.common、harness.environment 与 share 通知 API；连接注册解析、租约存储、传输通道与
 * READY 事件均由调用方通过窄端口注入（{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonRegistrationDirectory}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonLeaseStore}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonChannel}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener}、 {@link
 * fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalListener}），因此核心不感知
 * Spring 与持久化实现。
 *
 * <p>并发模型：同一 Environment 允许任意数量的 invocation 并发在途，仅以 invocationId 区分；不存在按 Environment
 * 的容量、信号量或排队。同一连接上的并发 {@code receive} 由 {@code gate} 串行化状态推进，listener 回调按该顺序由单一 drainer
 * 在核心锁外同步投递，因此并发调用不会重排 {@code PARTIAL* -> exactly one terminal} 的观察序列。每次发送前都以租约围栏复核归属，发送结果不确定时按
 * fail-closed 断开连接并失败在途调用。
 */
package fun.fengwk.kkstudio.harness.environment.server;
